package smartstock

import akka.actor.typed.{ActorSystem, DispatcherSelector}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import smartstock.anemone.{AnemoneClient, AnemoneThermal}
import java.net.URI
import smartstock.api.ApiServer
import smartstock.inventory._
import java.nio.file.Paths
import java.time.Clock
import scala.concurrent.Await
import scala.concurrent.duration._

object ServiceMain {
  def main(args: Array[String]): Unit = {
    val demo = args.contains("--demo")
    def setting(name: String, fallback: Double): Double =
      sys.env.get(name).map(_.toDouble).getOrElse(fallback)
    val limits = EnvironmentLimits(
      setting("SMARTSTOCK_TEMP_MIN", 15),
      setting("SMARTSTOCK_TEMP_MAX", 25),
      setting("SMARTSTOCK_HUMIDITY_MIN", 30),
      setting("SMARTSTOCK_HUMIDITY_MAX", 60)
    )
    val windowMillis = sys.env.getOrElse("SMARTSTOCK_CORRELATION_MS", "5000").toLong
    val toleranceGrams = setting("SMARTSTOCK_WEIGHT_TOLERANCE_GRAMS", 0.5)
    require(
      windowMillis > 0 && toleranceGrams.isFinite && toleranceGrams >= 0,
      "Invalid correlation configuration"
    )
    val port = sys.env.getOrElse("SMARTSTOCK_PORT", "8081").toInt
    require(port >= 0 && port <= 65535, "Invalid API port")
    val store = new FileInventoryStore(
      Paths.get(sys.env.getOrElse("SMARTSTOCK_DATA", "data/inventory.json"))
    )
    if (demo && store.load() == Inventory()) {
      val product = Product("compresses", "Compresses", "demo-compresses", 10, 5)
      val seeded = Inventory(
        products = Vector(product),
        batches = Vector(
          Batch("LOT-C", product.id, "cabinet", 40, "2027-06-30"),
          Batch("LOT-S", product.id, "bag", 4, "2027-06-30")
        )
      )
      store.save(seeded)
    }
    implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "SmartStockService")
    val inventory = system.systemActorOf(
      InventoryActor(store),
      "inventory",
      DispatcherSelector.fromConfig("smartstock.storage-dispatcher")
    )
    implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
    implicit val timeout: Timeout = Timeout(5.seconds)
    try Await.result(inventory.ask[Inventory](InventoryActor.Get.apply), 5.seconds)
    catch { case error: Exception => system.terminate(); throw error }
    val bridge =
      sys.env.get("SMARTSTOCK_ANEMONE_URL").map(url => new AnemoneClient(URI.create(url)))
    val server = new ApiServer(
      inventory,
      sys.env.getOrElse("SMARTSTOCK_BIND", "127.0.0.1"),
      port,
      demo,
      Clock.systemUTC(),
      bridge.map(new AnemoneThermal(_)),
      Some(new smartstock.events.StockInputs(inventory, bridge, windowMillis, toleranceGrams)),
      limits
    )
    server.start()
    println(s"SmartStock API http://127.0.0.1:${server.port}/api/v1/health (simulation=$demo)")
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      server.close(); bridge.foreach(_.close()); system.terminate()
    }))
    Await.ready(system.whenTerminated, Duration.Inf)
  }
}
