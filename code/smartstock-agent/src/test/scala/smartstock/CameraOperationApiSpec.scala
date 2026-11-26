package smartstock

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import smartstock.inventory._
import smartstock.api.ApiServer
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.UUID
import spray.json._

class CameraOperationApiSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  "Le statut d'une operation camera" should {
    "distinguer le scan recu de la confirmation du moteur et dedupliquer les replays" in {
      var persisted = Inventory(
        products = Vector(Product("p", "P", "05400711223344", 10, 0)),
        batches = Vector(
          Batch(
            "b",
            "p",
            "cabinet",
            40,
            "2029-12-31",
            Some("USB"),
            "historical-uninventoried",
            "Stock historique de test"
          )
        )
      )
      val store = new InventoryStore {
        def load(): Inventory = persisted; def save(s: Inventory): Unit = persisted = s
      }
      val actor = spawn(InventoryActor(store)); val server = new ApiServer(actor)(system);
      server.start()
      val client = HttpClient.newHttpClient()
      val operation = UUID.randomUUID().toString
      def get = client.send(
        HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:${server.port}/api/v1/operations/$operation"))
          .GET()
          .build(),
        HttpResponse.BodyHandlers.ofString()
      )
      def post(kind: String, data: JsObject, seq: Int, id: String) = {
        val payload = JsObject(
          "v" -> JsNumber(1),
          "id" -> JsString(id),
          "type" -> JsString(kind),
          "src" -> JsString("capture-" + operation.replace("-", "")),
          "seq" -> JsNumber(seq),
          "ts" -> JsString(java.time.Instant.now().toString),
          "corr" -> JsNull,
          "data" -> JsObject(
            data.fields ++ Map("batchId" -> JsString("b"), "operationId" -> JsString(operation))
          )
        )
        client.send(
          HttpRequest
            .newBuilder(URI.create(s"http://127.0.0.1:${server.port}/api/v1/events"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.compactPrint))
            .build(),
          HttpResponse.BodyHandlers.ofString()
        )
      }
      try {
        assert(get.statusCode() == 404)
        val scanId = UUID.randomUUID().toString
        val scan = JsObject(
          "code" -> JsString("05400711223344"),
          "serial" -> JsString("S1"),
          "lot" -> JsString("USB"),
          "expiration" -> JsString("2029-12-31")
        )
        assert(post("datamatrix.detected", scan, 1, scanId).statusCode() == 200)
        assert(get.body().parseJson.asJsObject.fields("status") == JsString("pending"))
        assert(persisted.batches.head.quantity == 40)
        val weightId = UUID.randomUUID().toString
        assert(
          post("weight.changed", JsObject("grams" -> JsNumber(-10)), 2, weightId)
            .statusCode() == 200
        )
        assert(get.body().parseJson.asJsObject.fields("status") == JsString("confirmed"))
        assert(persisted.batches.head.quantity == 39)
        assert(post("datamatrix.detected", scan, 1, scanId).statusCode() == 200)
        assert(persisted.movements.size == 1)
      } finally { client.close(); server.close(); testKit.stop(actor) }
    }
  }
}
