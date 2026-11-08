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
import java.util.concurrent.TimeUnit
import spray.json._

class ConcurrentApiSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  private def exercise(
      initial: Int
  )(check: (ApiServer, HttpClient, FileInventoryStore) => Unit): Unit = {
    val directory = Files.createTempDirectory("smartstock-concurrent-api")
    val file = directory.resolve("inventory.json")
    val store = new FileInventoryStore(file)
    store.save(
      Inventory(
        products = Vector(Product("p", "Compresses", "DM-P", 10, 1)),
        batches = Vector(Batch("b", "p", "cabinet", initial, "2027-06-30"))
      )
    )
    val actor = spawn(InventoryActor(store))
    val server = new ApiServer(actor)(system)
    server.start()
    try check(server, HttpClient.newHttpClient(), store)
    finally {
      server.close()
      testKit.stop(actor)
      Files.delete(file)
      Files.delete(directory)
    }
  }

  private def withdraw(server: ApiServer, client: HttpClient, ids: Seq[String]): Vector[Int] = {
    // Start every request before waiting: independent clients compete for the same batch.
    val pending = ids.map { id =>
      val request = HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:${server.port}/api/v1/movements"))
        .header("Content-Type", "application/json")
        .header("X-Command-Id", id)
        .POST(HttpRequest.BodyPublishers.ofString("""{"batchId":"b","delta":-1}"""))
        .build()
      client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
    }
    pending.map(_.get(15, TimeUnit.SECONDS).statusCode()).toVector
  }

  "API concurrente et stockage durable" should {
    "ne perdre aucune ecriture de commandes distinctes et exposer le meme stock aux lecteurs" in exercise(
      40
    ) { (server, client, store) =>
      val ids = Vector.fill(20)(UUID.randomUUID().toString)
      assert(withdraw(server, client, ids).forall(_ == 200))
      val request = HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:${server.port}/api/v1/snapshot"))
        .GET()
        .build()
      val snapshots = Vector
        .fill(4)(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
        .map(_.get(15, TimeUnit.SECONDS).body().parseJson.asJsObject)
      assert(snapshots.forall(_.fields("batches").convertTo[Vector[Batch]].head.quantity == 20))
      // Reload through the actual file store, independently of the actor's in-memory state.
      val recovered = store.load()
      assert(recovered.batches.head.quantity == 20)
      assert(recovered.movements.map(_.eventId).toSet == ids.toSet)
      assert(recovered.movements.map(_.quantityAfter).toSet == (20 until 40).toSet)
    }
    "ne compter quune fois des reemissions simultanees du meme identifiant" in exercise(40) {
      (server, client, store) =>
        val id = UUID.randomUUID().toString
        assert(withdraw(server, client, Vector.fill(20)(id)).forall(_ == 200))
        val recovered = store.load()
        assert(recovered.batches.head.quantity == 39)
        assert(recovered.movements.size == 1)
        assert(withdraw(server, client, Vector(id)).head == 200)
        assert(store.load().movements.size == 1)
    }
    "refuser les retraits concurrents qui depassent le dernier article" in exercise(1) {
      (server, client, store) =>
        val statuses = withdraw(server, client, Vector.fill(12)(UUID.randomUUID().toString))
        assert(statuses.count(_ == 200) == 1)
        assert(statuses.count(_ == 409) == 11)
        assert(store.load().batches.head.quantity == 0)
        assert(store.load().movements.size == 1)
    }
  }
}
