package smartstock

import akka.actor.typed.{ActorSystem, DispatcherSelector}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import smartstock.api.ApiServer
import smartstock.inventory._
import java.nio.file.{Path, Paths}
import java.time.Clock
import scala.concurrent.Await
import scala.concurrent.duration._
import spray.json._
import smartstock.inventory.InventoryJson._

object ServiceMain {
  def main(args: Array[String]): Unit = {
    val path = Paths.get(
      if (args.contains("--demo"))
        sys.env.getOrElse("SMARTSTOCK_DEMO_DATA", "data/demo-inventory.json")
      else sys.env.getOrElse("SMARTSTOCK_DATA", "data/inventory.json")
    )
    InventoryProcessLock.withLock(path)(storage => run(args, storage))
  }
  private def run(args: Array[String], storage: Path): Unit = {
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
      storage,
      allowExamples = demo
    )
    val catalogPath = Paths.get(
      sys.env.getOrElse(
        "SMARTSTOCK_CATALOG",
        if (demo) "config/demo-catalog.json" else "config/catalog.json"
      )
    )
    val catalog = Catalog.load(catalogPath, demo)
    val original = store.load()
    val catalogued = catalog.reconcile(original)
    require(
      !demo || original == Inventory() || original.dataset == "demo",
      "Stockage non marque Demo : choisir un nouveau SMARTSTOCK_DEMO_DATA, aucun stock existant modifie"
    )
    val initialized = if (demo && original == Inventory()) {
      val fixture =
        java.nio.file.Files.readString(Paths.get("config/demo-history.json")).parseJson.asJsObject
      require(
        fixture.fields("exampleData") == JsBoolean(true),
        "Jeu de demonstration marque requis"
      )
      catalog.reconcile(fixture.fields("inventory").convertTo[Inventory].copy(dataset = "demo"))
    } else catalogued
    if (initialized != original) store.save(initialized)
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
    val server = new ApiServer(
      inventory,
      sys.env.getOrElse("SMARTSTOCK_BIND", "127.0.0.1"),
      port,
      demo,
      Clock.systemUTC(),
      Some(new smartstock.events.StockInputs(inventory, windowMillis, toleranceGrams)),
      limits,
      catalog
    )
    server.start()
    println(s"SmartStock API http://127.0.0.1:${server.port}/api/v1/health (simulation=$demo)")
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      server.close(); system.terminate()
    }))
    Await.ready(system.whenTerminated, Duration.Inf)
  }
}
