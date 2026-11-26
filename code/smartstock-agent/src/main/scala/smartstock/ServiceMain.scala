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
    val root = ProjectPaths.root
    val legacy =
      sys.env.get(if (args.contains("--demo")) "SMARTSTOCK_DEMO_DATA" else "SMARTSTOCK_DATA")
    val directory =
      ProjectPaths.resolve(sys.env.getOrElse("SMARTSTOCK_DATA_DIR", "code/data"), root)
    require(
      legacy.isEmpty || !sys.env.contains("SMARTSTOCK_DATA_DIR"),
      "Deux stockages configures : choisir un seul chemin"
    )
    val lockPath =
      legacy.map(value => ProjectPaths.resolve(value, root)).getOrElse(directory.resolve("store"))
    InventoryProcessLock.withLock(lockPath) { storage =>
      val store: InventoryStore =
        if (legacy.nonEmpty) new FileInventoryStore(storage, args.contains("--demo"))
        else new JsonDirectoryStore(directory, args.contains("--demo"))
      println(s"Profil=${if (args.contains("--demo")) "Demo"
        else "Real"}; donnees=${if (legacy.nonEmpty) storage else directory}")
      store match {
        case json: JsonDirectoryStore =>
          println(
            "JSON persistants: " + json.files.mkString(
              ", "
            ) + "; transaction.json/commit.json internes"
          )
        case _ => ()
      }
      run(args, store, root)
    }
  }
  private def run(args: Array[String], store: InventoryStore, root: Path): Unit = {
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
    val catalogPath = ProjectPaths.resolve(
      sys.env.getOrElse(
        "SMARTSTOCK_CATALOG",
        if (demo) "code/smartstock-agent/config/demo-catalog.json"
        else "code/smartstock-agent/config/catalog.json"
      ),
      root
    )
    val catalog = Catalog.load(catalogPath, demo)
    val original = store.load()
    require(
      !demo || original == Inventory() || original.dataset == "demo",
      "Stockage non marque Demo : choisir un nouveau SMARTSTOCK_DEMO_DATA, aucun stock existant modifie"
    )
    require(
      !args.contains("--init-demo") || (demo && original == Inventory()),
      "Initialisation Demo explicite reservee a un stockage vide"
    )
    require(
      !args.contains("--init-catalog") || original == Inventory(),
      "Initialisation catalogue reservee a un stockage vide"
    )
    val initialized = if (args.contains("--init-demo")) {
      val fixture =
        java.nio.file.Files
          .readString(root.resolve("code/smartstock-agent/config/demo-history.json"))
          .parseJson
          .asJsObject
      require(
        fixture.fields("exampleData") == JsBoolean(true),
        "Jeu de demonstration marque requis"
      )
      catalog.reconcile(fixture.fields("inventory").convertTo[Inventory].copy(dataset = "demo"))
    } else if (args.contains("--init-catalog"))
      catalog.reconcile(original.copy(dataset = if (demo) "demo" else "real"))
    else if (original == Inventory() && demo) original.copy(dataset = "demo")
    else original
    val configured = if (initialized.serverSettings.isEmpty) {
      val legacy = ScanOnlySettings.fromEnvironment
      initialized.copy(serverSettings =
        Some(ServerSettings(withoutSensors = legacy.enabled && legacy.scaleDeclaredAbsent))
      )
    } else initialized
    if (configured != original) store.save(configured)
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
      catalog,
      ScanOnlySettings.fromEnvironment
    )
    server.start()
    val mqtt =
      try
        sys.env
          .get("SMARTSTOCK_MQTT_URL")
          .map(uri => {
            val http = java.net.http.HttpClient
              .newBuilder()
              .connectTimeout(java.time.Duration.ofSeconds(3))
              .build()
            val submit: String => Unit = payload => {
              val request = java.net.http.HttpRequest
                .newBuilder(java.net.URI.create(s"http://127.0.0.1:${server.port}/api/v1/events"))
                .timeout(java.time.Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                .build()
              val response =
                http.send(request, java.net.http.HttpResponse.BodyHandlers.discarding())
              if (response.statusCode() >= 400 && response.statusCode() < 500)
                system.log
                  .warn(
                    "MQTT envelope rejected by canonical API: {}",
                    Int.box(response.statusCode())
                  )
              else
                require(
                  response.statusCode() == 200,
                  s"Canonical API failed MQTT event: ${response.statusCode()}"
                )
            }
            val bridge =
              try
                new smartstock.events.HardwareMqtt(
                  uri,
                  sys.env.getOrElse("SMARTSTOCK_MQTT_TOPIC", "smartstock/events"),
                  inventory,
                  submit,
                  () => http.close()
                )
              catch {
                case scala.util.control.NonFatal(_) =>
                  http.close()
                  throw new IllegalArgumentException(
                    "Configuration MQTT invalide ; verifier URI/topic/identifiants locaux"
                  )
              }
            bridge.start()
            bridge
          })
      catch {
        case scala.util.control.NonFatal(error) =>
          server.close()
          system.terminate()
          throw error
      }
    println(s"SmartStock API http://127.0.0.1:${server.port}/api/v1/health (simulation=$demo)")
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      try mqtt.foreach(_.close())
      finally { server.close(); system.terminate() }
    }))
    Await.ready(system.whenTerminated, Duration.Inf)
  }
}
