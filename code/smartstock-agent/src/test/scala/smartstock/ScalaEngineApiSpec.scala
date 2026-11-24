package smartstock

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import smartstock.inventory._
import smartstock.inventory.InventoryJson._
import smartstock.api.ApiServer
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Files
import java.util.UUID
import spray.json._

class ScalaEngineApiSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  "API et moteur Scala/Akka autonome" should {
    "confirmer 40 vers39, dedupliquer et conserver lhysteresis apres redemarrage" in {
      val dir = Files.createTempDirectory("scala-engine-api")
      val file = dir.resolve("inventory.json")
      val store = new FileInventoryStore(file)
      store.save(
        Inventory(
          products = Vector(Product("p", "Compresses", "DM-P", 10, 5)),
          batches = Vector(Batch("b", "p", "cabinet", 40, "2027-06-30"))
        )
      )
      val client = HttpClient.newHttpClient()
      var actor = spawn(InventoryActor(store))
      var server = new ApiServer(actor)(system)
      server.start()
      def request(method: String, path: String, body: JsValue = JsNull): (Int, JsValue) = {
        val builder = HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:${server.port}$path"))
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(body.compactPrint))
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        response.statusCode() -> response.body().parseJson
      }
      def event(kind: String, seq: Int, data: JsObject): JsObject = JsObject(
        "v" -> JsNumber(1),
        "id" -> JsString(UUID.randomUUID().toString),
        "type" -> JsString(kind),
        "src" -> JsString("cabinet-01"),
        "seq" -> JsNumber(seq),
        "ts" -> JsString("2026-11-10T12:00:00Z"),
        "corr" -> JsNull,
        "data" -> data
      )
      def send(e: JsObject): Unit = assert(request("POST", "/api/v1/events", e)._1 == 200)
      def state: JsObject = request("GET", "/api/v1/snapshot")._2.asJsObject
      def quantity: Int = state.fields("batches").convertTo[Vector[Batch]].head.quantity
      def fan: Double = state.fields("readings").convertTo[Vector[Reading]].last.value
      def temp(seq: Int, value: Double): JsObject =
        event("temperature.measured", seq, JsObject("value" -> JsNumber(value)))
      try {
        val operation = UUID.randomUUID().toString
        val base = Map("batchId" -> JsString("b"), "operationId" -> JsString(operation))
        val weight = event("weight.changed", 1, JsObject(base + ("grams" -> JsNumber(-10))))
        val scan = event("datamatrix.detected", 2, JsObject(base + ("code" -> JsString("DM-P"))))
        send(weight); assert(quantity == 40)
        send(scan); send(scan); assert(quantity == 39)
        assert(state.fields("movements").convertTo[Vector[Movement]].size == 1)
        val hot = temp(3, 27)
        send(hot); assert(fan == 75)
        send(temp(4, 25.9)); assert(fan == 75)
        assert(store.load().readings.count(_.kind == "temperature.measured") == 2)
        server.close(); testKit.stop(actor)
        actor = spawn(InventoryActor(new FileInventoryStore(file)))
        server = new ApiServer(actor)(system); server.start()
        send(scan); assert(quantity == 39)
        send(hot); assert(fan == 75)
        send(temp(5, 25.5)); assert(fan == 75)
        send(temp(6, 25.4)); assert(fan == 50)
        send(hot); assert(fan == 50)
        assert(request("POST", "/api/v1/events", temp(6, 30))._1 == 409)
        assert(fan == 50)
        assert(state.fields("movements").convertTo[Vector[Movement]].size == 1)
      } finally {
        server.close(); testKit.stop(actor); client.close()
        Files.delete(file); Files.delete(dir)
      }
    }
  }
}
