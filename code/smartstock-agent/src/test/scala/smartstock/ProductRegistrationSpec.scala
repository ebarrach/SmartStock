package smartstock

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import smartstock.inventory._
import smartstock.api.ApiServer
import smartstock.inventory.InventoryJson._
import java.nio.file.Files
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.UUID
import spray.json._

class ProductRegistrationSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  "L'enregistrement apres scan" should {
    "persister un nom confirme sans masse ni presence inventee, reutiliser le lot et dedupliquer" in {
      val file = Files.createTempDirectory("registered-product").resolve("inventory.json")
      val store = new FileInventoryStore(file)
      val actor = spawn(InventoryActor(store))
      val server = new ApiServer(actor, scanOnlySettings = ScanOnlySettings(true, true))(system);
      server.start()
      val client = HttpClient.newHttpClient()
      def post(path: String, value: JsValue, id: String = UUID.randomUUID().toString) = client.send(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:${server.port}/api/v1$path"))
          .header("Content-Type", "application/json")
          .header("X-Command-Id", id)
          .POST(HttpRequest.BodyPublishers.ofString(value.compactPrint))
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      val product = new Product(
        "gtin-07613421008642",
        "Nom saisi par l'utilisateur (test)",
        "07613421008642",
        None,
        0
      )
      val batch = Batch(
        "new-batch",
        product.id,
        "cabinet",
        0,
        "2028-03-31",
        Some("PM9263"),
        "inventoried",
        "Lot confirme vide, aucune presence initiale deduite"
      )
      try {
        val key = UUID.randomUUID().toString
        assert(post("/products", product.toJson, key).statusCode() == 200)
        assert(post("/products", product.toJson, key).statusCode() == 200)
        assert(store.load().products.head.unitWeightGrams.isEmpty)
        assert(product.toJson.asJsObject.fields("unitWeightGrams") == JsNull)
        val equivalent = product.copy(id = "duplicate", dataMatrix = "7613421008642")
        assert(post("/products", equivalent.toJson).statusCode() == 409)
        assert(post("/batches/empty", batch.toJson).statusCode() == 200)
        assert(post("/batches/empty", batch.copy(id = "race").toJson).statusCode() == 200)
        assert(
          store.load().products.size == 1 && store.load().batches.size == 1 && store
            .load()
            .movements
            .isEmpty
        )
        val scan = JsObject(
          "gtin" -> JsString(product.dataMatrix),
          "lot" -> JsString("PM9263"),
          "expiration" -> JsString("2028-03-31"),
          "serial" -> JsString("10463996231392")
        )
        assert(
          post("/scan-only-test", scan).body().parseJson.asJsObject.fields("code") == JsString(
            "presence_unknown"
          )
        )
        val entry = JsObject(scan.fields + ("direction" -> JsString("entry")))
        val command = UUID.randomUUID().toString
        assert(post("/scan-only-test", entry, command).statusCode() == 200)
        assert(post("/scan-only-test", entry, command).statusCode() == 200)
        assert(store.load().batches.head.quantity == 1 && store.load().movements.size == 1)
        assert(store.load().products.head.name == product.name && store.load().readings.isEmpty)
        val correlation = new smartstock.events.Correlator()
        val input = smartstock.events.ScanInput(
          UUID.randomUUID(),
          UUID.randomUUID(),
          "device",
          batch.id,
          product.dataMatrix
        )
        val prepared =
          correlation.accept(smartstock.events.CorrelationState(), input, product, batch, 0)
        assert(prepared._2 == Right(None))
        val weight = smartstock.events.WeightInput(
          UUID.randomUUID(),
          input.operationId,
          input.source,
          input.batchId,
          10
        )
        assert(
          correlation
            .accept(prepared._1, weight, product, batch, 1)
            ._2
            .left
            .toOption
            .get
            .code == "unit_weight_missing"
        )
      } finally {
        client.close(); server.close(); testKit.stop(actor); Files.deleteIfExists(file);
        Files.deleteIfExists(file.getParent)
      }
    }
    "distinguer produit inconnu, plusieurs correspondances et nouveau lot" in {
      val p = new Product("p", "Nom reel fourni", "07613421008642", None, 0)
      val scan = ScanOnlyTest(
        p.dataMatrix,
        "PM9263",
        "2028-03-31",
        Some("S1"),
        "auto",
        ScanOnlySettings(true, true)
      )
      def code(state: Inventory) = InventoryRules
        .execute(state, scan, UUID.randomUUID().toString, java.time.Clock.systemUTC())
        .left
        .toOption
        .get
        .code
      assert(code(Inventory()) == "unknown_product")
      assert(
        code(
          Inventory(products = Vector(p, p.copy(id = "other", dataMatrix = "7613421008642")))
        ) == "ambiguous_product"
      )
      assert(code(Inventory(products = Vector(p))) == "new_batch")
    }
  }
}
