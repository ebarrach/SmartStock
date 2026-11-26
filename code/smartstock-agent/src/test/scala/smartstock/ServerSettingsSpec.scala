package smartstock

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import smartstock.inventory._
import smartstock.inventory.InventoryJson._
import smartstock.api.ApiServer
import java.time.{Clock, Instant, ZoneOffset}
import java.nio.file.Files
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.UUID
import spray.json._

class ServerSettingsSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  private def id = UUID.randomUUID().toString
  private val now = Instant.parse("2026-11-26T18:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)
  private val product = Product("p", "Nom reel du produit", "07613421008642", 10, 5)
  private val batch = Batch("b", "p", "cabinet", 2, "2020-01-01", Some("LOT"), "inventoried")
  private val initial = Inventory(products = Vector(product), batches = Vector(batch))
  private def run(s: Inventory, op: InventoryOperation): Inventory =
    InventoryRules.execute(s, op, id, clock).toOption.get
  "Les parametres canoniques" should {
    "ne pas publier le mode lorsque son ecriture echoue" in {
      val actor = spawn(InventoryActor(new InventoryStore {
        def load(): Inventory = initial
        def save(state: Inventory): Unit = throw new java.io.IOException("test")
      }))
      val reply = createTestProbe[Either[DomainError, Inventory]]()
      actor ! InventoryActor.Execute(SetSensorMode(true), id, reply.ref)
      assert(reply.receiveMessage().left.toOption.get.code == "storage_unavailable")
      val current = createTestProbe[Inventory]()
      actor ! InventoryActor.Get(current.ref)
      assert(current.receiveMessage() == initial)
    }
    "persister le mode explicite sans perdre observations, quantites ou historique" in {
      val file = Files.createTempDirectory("server-mode").resolve("state.json")
      val store = new FileInventoryStore(file)
      val source = initial.copy(sensorInputs =
        Vector(
          SensorRecord(
            id,
            id,
            "scale",
            "b",
            "weight.changed",
            JsNumber(10),
            0,
            clock.millis() - 100
          )
        )
      )
      val next = run(source, SetSensorMode(true))
      store.save(next)
      assert(store.load() == next)
      assert(next.batches == source.batches && next.sensorInputs == source.sensorInputs)
      val scan = ScanOnlyTest(
        product.dataMatrix,
        "LOT",
        batch.expiresOn,
        Some("new"),
        "entry",
        ScanOnlySettings()
      )
      val added = run(next, scan)
      assert(added.batches.head.quantity == 3)
      val normal = run(added, SetSensorMode(false))
      assert(
        InventoryRules
          .execute(normal, scan, id, clock)
          .left
          .toOption
          .get
          .code == "scan_only_disabled"
      )
      assert(normal.serverSettings.exists(!_.withoutSensors))
    }
    "interdire une confirmation materielle lorsque le mode change" in {
      val state = run(initial, SetSensorMode(true))
      assert(
        InventoryRules.execute(state, ConfirmSensorStock(AdjustStock("b", -1), 0), id, clock).isLeft
      )
      assert(state.batches.head.quantity == 2)
    }
    "calibrer seulement depuis des mesures recentes reelles, avec facteur signe et relecture" in {
      assert(InventoryRules.execute(initial, CaptureScaleTare("hx711"), id, clock).isLeft)
      val empty = initial.copy(readings =
        (1 to 3)
          .map(i => Reading("weight.raw", 100, "hx711", now.minusSeconds(i).toString))
          .toVector
      )
      val tare = run(empty, CaptureScaleTare("hx711"))
      val tareId = tare.serverSettings.get.tare.get.id
      assert(InventoryRules.execute(tare, CalibrateScale("hx711", 100, tareId), id, clock).isLeft)
      val later = Clock.fixed(now.plusSeconds(5), ZoneOffset.UTC)
      val loaded = tare.copy(readings =
        tare.readings ++ (1 to 3).map(i =>
          Reading("weight.raw", -900, "hx711", now.plusSeconds(i).toString)
        )
      )
      val calibrated =
        InventoryRules.execute(loaded, CalibrateScale("hx711", 100, tareId), id, later).toOption.get
      assert(calibrated.serverSettings.get.calibration.get.gramsPerRaw == -0.1)
      assert(calibrated.toJson.convertTo[Inventory] == calibrated)
      assert(InventoryRules.execute(loaded, CalibrateScale("other", 100, tareId), id, later).isLeft)
      assert(InventoryRules.execute(loaded, CalibrateScale("hx711", -1, tareId), id, later).isLeft)
      assert(calibrated.batches == initial.batches && calibrated.movements.isEmpty)
    }
    "archiver seulement le produit confirme sans supprimer son historique" in {
      val archived = run(initial, ArchiveProduct("p", product.dataMatrix))
      assert(archived.products == initial.products && archived.batches == initial.batches)
      assert(InventoryRules.alerts(archived, clock).isEmpty)
      assert(InventoryRules.execute(initial, ArchiveProduct("p", "wrong"), id, clock).isLeft)
      assert(InventoryRules.alerts(initial, clock).forall(_.message.contains(product.name)))
    }
    "exposer le mode persistant et convertir seulement un poids avec calibration et operation partagee" in {
      val file = Files.createTempDirectory("settings-api").resolve("state.json")
      val store = new FileInventoryStore(file)
      store.save(
        initial.copy(serverSettings =
          Some(
            ServerSettings(calibration =
              Some(ScaleCalibration("hx711", 100, 1100, 100, now.toString, id))
            )
          )
        )
      )
      val actor = spawn(InventoryActor(store))
      val server = new ApiServer(actor)(system)
      server.start()
      val client = HttpClient.newHttpClient()
      def request(path: String, method: String, data: JsValue) = client.send(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:${server.port}/api/v1$path"))
          .header("Content-Type", "application/json")
          .header("X-Command-Id", id)
          .method(method, HttpRequest.BodyPublishers.ofString(data.compactPrint))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      val op = id
      val capture = id
      def envelope(kind: String, seq: Int, data: Map[String, JsValue]) = JsObject(
        "v" -> JsNumber(1),
        "id" -> JsString(id),
        "type" -> JsString(kind),
        "src" -> JsString("capture-" + capture.replace("-", "")),
        "seq" -> JsNumber(seq),
        "ts" -> JsString(Instant.now().toString),
        "corr" -> JsNull,
        "data" -> JsObject(data ++ Map("operationId" -> JsString(op), "batchId" -> JsString("b")))
      )
      try {
        assert(
          request(
            "/events",
            "POST",
            envelope("datamatrix.detected", 1, Map("code" -> JsString(product.dataMatrix)))
          ).statusCode() == 200
        )
        assert(store.load().batches.head.quantity == 2)
        val hardware = JsObject(
          "source" -> JsString("hx711"),
          "operationId" -> JsString(op),
          "captureId" -> JsString(capture),
          "rawDelta" -> JsNumber(-100)
        )
        val weight = smartstock.events.HardwareOperationAdapter
          .envelope(hardware.compactPrint, store.load())
          .toOption
          .get
          .get
          .parseJson
        assert(request("/events", "POST", weight).statusCode() == 200)
        assert(request("/events", "POST", weight).statusCode() == 200)
        assert(store.load().batches.head.quantity == 1 && store.load().movements.size == 1)
        assert(store.load().sensorInputs.last.value.asJsObject.fields("rawDelta") == JsNumber(-100))
        assert(
          request("/settings/sensors", "PUT", JsObject("withoutSensors" -> JsBoolean(true)))
            .statusCode() == 200
        )
        assert(store.load().serverSettings.get.withoutSensors)
      } finally { client.close(); server.close() }
    }
  }
}
