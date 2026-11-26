package smartstock

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import smartstock.inventory._
import smartstock.events.StockInputs
import java.time.Clock
import java.nio.file.Files
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration._
import spray.json._

class HistoricalSerialSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  private val batch = Batch(
    "old-id",
    "p",
    "cabinet",
    40,
    "2027-12-31",
    Some("L"),
    "historical-uninventoried",
    "Reprise verifiee sans inventaire initial des series"
  )
  private val initial =
    Inventory(products = Vector(Product("p", "P", "GTIN", 10, 1)), batches = Vector(batch))
  private def id = UUID.randomUUID().toString
  private def withdrawal = AdjustStock("old-id", -1, kind = "consumption", serial = Some("S1"))
  private def run(
      state: Inventory,
      op: InventoryOperation,
      key: String = UUID.randomUUID().toString
  ) = InventoryRules.execute(state, op, key, Clock.systemUTC())
  "La reprise historique explicite" should {
    "tracer seulement le retrait initial sans entree ni increment fictif" in {
      val result = run(initial, withdrawal).toOption.get
      assert(result.batches.head.quantity == 39 && result.movements.size == 1)
      assert(result.movements.head.initialPresenceUnknown)
      assert(result.movements.head.reason == "présence initiale inconnue — retrait validé")
      assert(run(result, withdrawal).left.toOption.get.code == "serial_state_conflict")
      val returned = run(result, withdrawal.copy(delta = 1, kind = "replenishment")).toOption.get
      assert(!returned.movements.last.initialPresenceUnknown)
      assert(run(returned, withdrawal).toOption.get.batches.head.quantity == 39)
    }
    "refuser une serie inconnue sans classification explicite ou dans un lot inventorie" in {
      for (status <- Vector("unclassified", "inventoried"))
        assert(
          run(
            initial.copy(batches = Vector(batch.copy(serialInventoryStatus = status))),
            withdrawal
          ).left.toOption.get.code == "unknown_serial"
        )
      assert(
        run(
          Inventory(products = initial.products),
          RegisterBatch(batch.copy(traceabilityNote = ""))
        ).isLeft
      )
    }
    "refuser stock insuffisant, lot ambigu et historique de serie contradictoire" in {
      assert(
        run(
          initial.copy(batches = Vector(batch.copy(quantity = 0))),
          withdrawal
        ).left.toOption.get.code == "stock_conflict"
      )
      assert(
        run(
          initial.copy(batches = Vector(batch, batch.copy(id = "duplicate"))),
          withdrawal
        ).left.toOption.get.code == "ambiguous_batch"
      )
      val contradictory =
        Movement(id, "other-lot", -1, 5, "2026-11-26T10:00:00Z", id, "p", serial = Some("S1"))
      assert(
        run(
          initial.copy(movements = Vector(contradictory)),
          withdrawal.copy(delta = 1, kind = "replenishment")
        ).left.toOption.get.code == "serial_batch_conflict"
      )
    }
    "enregistrer une classification sans mouvement de stock et dedupliquer sa commande" in {
      val old = initial.copy(batches =
        Vector(
          batch.copy(lotCode = None, serialInventoryStatus = "unclassified", traceabilityNote = "")
        )
      )
      val op = ConfigureBatchTraceability(
        "old-id",
        "L",
        "historical-uninventoried",
        "Inventaire historique verifie"
      )
      val key = id
      val configured = run(old, op, key).toOption.get
      assert(configured.batches.head.quantity == 40 && configured.movements.isEmpty)
      assert(run(configured, op, key) == Right(configured))
    }
    "ne rien publier partiellement apres echec du mouvement et reprendre les recus persistes" in {
      implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
      val file = Files.createTempDirectory("historical-atomic").resolve("inventory.json")
      val disk = new FileInventoryStore(file)
      disk.save(initial)
      var fail = true
      val store = new InventoryStore {
        def load(): Inventory = disk.load()
        def save(state: Inventory): Unit = {
          if (fail && state.movements.nonEmpty)
            throw new java.io.IOException("injected write failure")
          disk.save(state)
        }
      }
      var actor = spawn(InventoryActor(store))
      var pipeline = new StockInputs(actor)(system, ec)
      val op = id
      val scan = SensorRecord(
        id,
        op,
        "scanner",
        "old-id",
        "datamatrix.detected",
        JsObject("code" -> JsString("GTIN"), "serial" -> JsString("S1"), "lot" -> JsString("L")),
        1,
        1000
      )
      val weight =
        SensorRecord(id, op, "scanner", "old-id", "weight.changed", JsNumber(-10), 2, 1100)
      assert(Await.result(pipeline.submit(scan), 5.seconds) == Right(false))
      assert(
        Await
          .result(pipeline.submit(weight), 5.seconds)
          .left
          .toOption
          .get
          .code == "storage_unavailable"
      )
      val probe = createTestProbe[Inventory]()
      actor ! InventoryActor.Get(probe.ref)
      val memory = probe.receiveMessage()
      assert(memory.batches.head.quantity == 40 && memory.movements.isEmpty)
      assert(disk.load() == memory && memory.sensorInputs.size == 2)
      testKit.stop(actor)
      fail = false
      actor = spawn(InventoryActor(store))
      pipeline = new StockInputs(actor)(system, ec)
      assert(Await.result(pipeline.submit(weight), 5.seconds) == Right(true))
      assert(Await.result(pipeline.submit(weight), 5.seconds) == Right(true))
      assert(disk.load().batches.head.quantity == 39 && disk.load().movements.size == 1)
      assert(disk.load().movements.head.initialPresenceUnknown)
      testKit.stop(actor)
      Files.delete(file); Files.delete(file.getParent)
    }
  }
}
