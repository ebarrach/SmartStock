package smartstock

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import smartstock.inventory._
import smartstock.api.ApiServer
import smartstock.inventory.InventoryJson._
import java.time.{Clock, Instant, ZoneOffset}
import java.nio.file.Files
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.UUID
import spray.json._

class ScanOnlyTestSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  val clock = Clock.fixed(Instant.parse("2026-11-26T10:00:00Z"), ZoneOffset.UTC)
  val product = Product("usb", "Exemple USB", "05400711223344", 10, 0)
  val batch = Batch(
    "USB-LOT",
    "usb",
    "cabinet",
    0,
    "2029-12-31",
    Some("USB"),
    "inventoried",
    "Fixture logicielle : serie connue absente"
  )
  val initial = Inventory(
    products = Vector(product),
    batches = Vector(batch),
    movements = Vector(
      Movement(
        UUID.randomUUID().toString,
        batch.id,
        -1,
        0,
        "2026-11-25T10:00:00Z",
        UUID.randomUUID().toString,
        product.id,
        "consumption",
        "fixture",
        "Exemple uniquement : absence deja connue",
        Some("S1")
      )
    )
  )
  def id: String = UUID.randomUUID().toString
  val scan = ScanOnlyTest(
    product.dataMatrix,
    "USB",
    batch.expiresOn,
    Some("S1"),
    "auto",
    ScanOnlySettings(true, true)
  )
  def run(s: Inventory, op: ScanOnlyTest = scan, key: String = UUID.randomUUID().toString) =
    InventoryRules.execute(s, op, key, clock)

  "Le test sans balance" should {
    "ajouter une serie connue absente, dedupliquer et retirer au scan volontaire suivant" in {
      val key = id
      val entered = run(initial, key = key).toOption.get
      assert(entered.batches.head.quantity == 1 && entered.movements.last.delta == 1)
      assert(entered.movements.last.reason.contains("test sans balance"))
      assert(run(entered, key = key) == Right(entered))
      val retired = run(entered).toOption.get
      assert(retired.batches.head.quantity == 0 && retired.movements.size == 3)
      assert(retired.readings.isEmpty)
    }
    "refuser sans activation ou absence declaree et ne pas confondre zero avec deconnexion" in {
      assert(
        run(
          initial,
          scan.copy(settings = ScanOnlySettings())
        ).left.toOption.get.code == "scan_only_disabled"
      )
      assert(run(initial, scan.copy(settings = ScanOnlySettings(true, false))).isLeft)
      val rawZero = Reading("weight.raw", 0, "scale", clock.instant().toString)
      assert(
        run(initial.copy(readings = Vector(rawZero))).left.toOption.get.code == "scale_connected"
      )
      val connected = Reading("sensor.failed", 0, "mkr-hx711", "2026-11-20T10:00:00Z")
      assert(run(initial.copy(readings = Vector(connected))).isLeft)
      val calibrated =
        SensorRecord(id, id, "gateway", batch.id, "weight.changed", JsNumber(10), 1, clock.millis())
      assert(
        run(
          initial.copy(sensorInputs = Vector(calibrated))
        ).left.toOption.get.code == "scale_connected"
      )
      assert(
        run(
          initial.copy(readings = Vector(rawZero.copy(observedAt = "2026-11-25T10:00:00Z")))
        ).isRight
      )
    }
    "refuser produit inconnu, lot ambigu et presence inconnue sans deduire de la quantite" in {
      assert(run(initial, scan.copy(gtin = "99999999999999")).isLeft)
      assert(run(initial.copy(batches = Vector(batch, batch.copy(id = "other")))).isLeft)
      val unknown = scan.copy(serial = Some("UNKNOWN"))
      assert(
        run(
          initial.copy(batches = Vector(batch.copy(quantity = 40))),
          unknown
        ).left.toOption.get.code == "presence_unknown"
      )
      assert(
        run(initial, unknown.copy(direction = "exit")).left.toOption.get.code == "unknown_serial"
      )
      assert(run(initial, scan.copy(serial = None)).isLeft)
      assert(run(initial, scan.copy(serial = None, direction = "entry")).isRight)
    }
    "conserver les conditions de reprise historique pour un retrait explicite" in {
      val old = initial.copy(batches =
        Vector(batch.copy(quantity = 40, serialInventoryStatus = "historical-uninventoried"))
      )
      val entered = run(old, scan.copy(serial = Some("UNKNOWN"), direction = "exit")).toOption.get
      assert(entered.movements.last.initialPresenceUnknown)
      assert(entered.movements.last.reason.contains("test sans balance"))
      assert(run(entered, scan.copy(serial = Some("UNKNOWN"), direction = "exit")).isLeft)
    }
    "rester coherent par HTTP apres retry, redemarrage et echec de stockage" in {
      val file = Files.createTempDirectory("scan-only-api").resolve("inventory.json")
      val disk = new FileInventoryStore(file); disk.save(initial)
      var fail = true
      val store = new InventoryStore {
        def load(): Inventory = disk.load()
        def save(s: Inventory): Unit = {
          if (fail) throw new java.io.IOException("injected"); disk.save(s)
        }
      }
      var actor = spawn(InventoryActor(store, clock))
      var server =
        new ApiServer(actor, clock = clock, scanOnlySettings = ScanOnlySettings(true, true))(system)
      server.start()
      val client = HttpClient.newHttpClient()
      val key = id
      def post(command: String): HttpResponse[String] = client.send(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:${server.port}/api/v1/scan-only-test"))
          .header("Content-Type", "application/json")
          .header("X-Command-Id", command)
          .POST(
            HttpRequest.BodyPublishers.ofString(
              s"""{"gtin":"${product.dataMatrix}","lot":"USB","expiration":"2029-12-31","serial":"S1"}"""
            )
          )
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      try {
        assert(post(key).statusCode() == 503 && disk.load() == initial)
        fail = false
        assert(post(key).statusCode() == 200 && post(key).statusCode() == 200)
        assert(disk.load().batches.head.quantity == 1)
        server.close(); testKit.stop(actor)
        actor = spawn(InventoryActor(store, clock))
        server =
          new ApiServer(actor, clock = clock, scanOnlySettings = ScanOnlySettings(true, true))(
            system
          );
        server.start()
        assert(post(key).statusCode() == 200 && disk.load().batches.head.quantity == 1)
        assert(post(id).statusCode() == 200 && disk.load().batches.head.quantity == 0)
      } finally {
        server.close(); testKit.stop(actor); client.close(); Files.delete(file);
        Files.delete(file.getParent)
      }
    }
  }
}
