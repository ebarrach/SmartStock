package smartstock

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import smartstock.inventory._
import smartstock.api.ApiServer
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.UUID
import spray.json._
import InventoryJson._

class ApiSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  "API HTTP reelle" should {
    "exposer catalogue, stock, erreurs, mouvements et mesures conformes" in {
      val store = new InventoryStore {
        def load(): Inventory = Inventory()
        def save(state: Inventory): Unit = ()
      }
      val actor = spawn(InventoryActor(store))
      val server = new ApiServer(actor, simulated = true)(system)
      server.start()
      val client = HttpClient.newHttpClient()
      def request(
          method: String,
          path: String,
          body: String = "",
          id: String = UUID.randomUUID().toString
      ): HttpResponse[String] = {
        val builder = HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:${server.port}$path"))
          .header("Content-Type", "application/json")
          .header("X-Command-Id", id)
          .method(method, HttpRequest.BodyPublishers.ofString(body))
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
      }
      try {
        assert(request("GET", "/api/v1/health").statusCode() == 200)
        assert(request("POST", "/api/v1/products", "{").statusCode() == 400)
        assert(
          request("POST", "/api/v1/products", """{"id":"x","extra":true}""").statusCode() == 400
        )
        val product = Product("compresses", "Compresses", "DM-C", 10, 5)
        assert(request("POST", "/api/v1/products", product.toJson.compactPrint).statusCode() == 200)
        val batch = Batch("LOT-C", product.id, "cabinet", 40, "2027-06-30")
        assert(request("POST", "/api/v1/batches", batch.toJson.compactPrint).statusCode() == 200)
        assert(
          request(
            "PUT",
            "/api/v1/batches/LOT-C/traceability",
            """{"lotCode":"C","status":"historical-uninventoried","note":"Reprise historique explicitement verifiee"}"""
          ).statusCode() == 200
        )
        assert(
          request("GET", "/api/v1/snapshot")
            .body()
            .parseJson
            .asJsObject
            .fields("movements") == JsArray()
        )
        val command = """{"batchId":"LOT-C","delta":-1}"""
        val key = UUID.randomUUID().toString
        assert(request("POST", "/api/v1/movements", command, key).statusCode() == 200)
        assert(request("POST", "/api/v1/movements", command, key).statusCode() == 200)
        assert(request("POST", "/api/v1/movements", command, key.toUpperCase).statusCode() == 200)
        assert(request("POST", "/api/v1/movements", command, "1-1-1-1-1").statusCode() == 400)
        val snapshot = request("GET", "/api/v1/snapshot").body().parseJson.asJsObject.fields
        assert(snapshot("batches").convertTo[Vector[Batch]].head.quantity == 39)
        assert(snapshot("movements").convertTo[Vector[Movement]].size == 1)
        assert(request("GET", "/api/health").statusCode() == 200)
        assert(
          request("GET", "/api/inventory")
            .body()
            .parseJson
            .asJsObject
            .fields("stock")
            .asJsObject
            .fields("LOT-C") == JsNumber(39)
        )
        assert(
          request("GET", "/api/expirations?days=3650")
            .body()
            .parseJson
            .asJsObject
            .fields("items")
            .convertTo[Vector[JsValue]]
            .size == 1
        )
        assert(request("GET", "/api/expirations?days=-1").statusCode() == 400)
        assert(request("GET", "/api/expirations?days=30&days=60").statusCode() == 400)
        assert(request("POST", "/api/scans", "{}").statusCode() == 409)
        assert(request("GET", "/api/inventory").body().contains("39"))
        assert(!snapshot.contains("receipts"))
        assert(request("GET", "/api/v1/products/unknown").statusCode() == 404)
        assert(request("GET", "/api/v1/stock?location=bag").body() == "[]")
        assert(request("GET", "/api/v1/stock?location=bad").statusCode() == 400)
        assert(request("DELETE", "/api/v1/products").statusCode() == 405)
        val event = s"""{"v":1,"id":"${UUID
            .randomUUID()}","type":"temperature.measured","src":"cabinet-01","seq":1,"ts":"2026-10-29T00:00:00Z","corr":null,"data":{"value":20}}"""
        assert(request("POST", "/api/v1/events", event).statusCode() == 200)
        assert(
          request("GET", "/api/v1/readings").body().parseJson.convertTo[Vector[Reading]].size == 2
        )
        val refill =
          """{"batchId":"LOT-C","delta":1,"kind":"replenishment","source":"operator","reason":"livraison"}"""
        val refillId = UUID.randomUUID().toString
        assert(request("POST", "/api/v1/movements", refill, refillId).statusCode() == 200)
        assert(request("POST", "/api/v1/movements", refill, refillId).statusCode() == 200)
        val movements =
          request("GET", "/api/v1/movements").body().parseJson.convertTo[Vector[Movement]]
        assert(
          movements.size == 2 && movements.last.kind == "replenishment" && movements.last.source == "operator" && movements.last.reason == "livraison"
        )
        assert(request("GET", "/api/v1/analytics?months=6").statusCode() == 200)
        assert(request("GET", "/api/v1/analytics?months=2").statusCode() == 400)
      } finally { server.close(); client.close() }
    }
  }
}
