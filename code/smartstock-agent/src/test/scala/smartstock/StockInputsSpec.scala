package smartstock

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import smartstock.inventory._
import smartstock.events.StockInputs
import java.nio.file.Files
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration._
import spray.json._

class StockInputsSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  implicit val ec: scala.concurrent.ExecutionContext = system.executionContext
  val initial = Inventory(
    products = Vector(Product("compresses", "Compresses", "DM-C", 10, 5)),
    batches = Vector(Batch("LOT-C", "compresses", "cabinet", 40, "2027-06-30"))
  )
  def input(op: String, kind: String, value: JsValue, seq: Long, at: Long): SensorRecord =
    SensorRecord(UUID.randomUUID().toString, op, "cabinet-01", "LOT-C", kind, value, seq, at)

  "StockInputs" should {
    "tracer une presence initiale inconnue et conserver la presence unitaire apres redemarrage" in {
      val file = Files.createTempDirectory("serial-ledger").resolve("inventory.json")
      val store = new FileInventoryStore(file)
      store.save(initial)
      var actor = spawn(InventoryActor(store))
      var pipeline = new StockInputs(actor)(system, ec)
      var sequence = 0L
      def operation(serial: String, grams: Int): Either[DomainError, Boolean] = {
        val op = UUID.randomUUID().toString
        sequence += 1
        val scan = input(
          op,
          "datamatrix.detected",
          JsObject("code" -> JsString("DM-C"), "serial" -> JsString(serial)),
          sequence,
          1000
        )
        assert(Await.result(pipeline.submit(scan), 5.seconds) == Right(false))
        sequence += 1
        Await.result(
          pipeline.submit(input(op, "weight.changed", JsNumber(grams), sequence, 1100)),
          5.seconds
        )
      }
      assert(operation("UNIT-1", -10) == Right(true))
      assert(store.load().movements.last.initialPresenceUnknown)
      assert(store.load().movements.last.serial.contains("UNIT-1"))
      testKit.stop(actor)
      actor = spawn(InventoryActor(new FileInventoryStore(file)))
      pipeline = new StockInputs(actor)(system, ec)
      assert(operation("UNIT-1", -10).left.toOption.get.code == "serial_state_conflict")
      assert(store.load().batches.head.quantity == 39)
      assert(operation("UNIT-2", -10) == Right(true))
      assert(operation("UNIT-1", 10) == Right(true))
      assert(!store.load().movements.last.initialPresenceUnknown)
      assert(operation("UNIT-1", 10).left.toOption.get.code == "serial_state_conflict")
      assert(operation("UNIT-1", -20).isLeft)
      assert(store.load().batches.head.quantity == 39)
      testKit.stop(actor)
      Files.delete(file); Files.delete(file.getParent)
    }
    "reprendre un couple persiste apres redemarrage sans doubler le mouvement" in {
      val directory = Files.createTempDirectory("stock-inputs")
      val file = directory.resolve("inventory.json")
      val store = new FileInventoryStore(file)
      store.save(initial)
      val actor = spawn(InventoryActor(store))
      val op = UUID.randomUUID().toString
      val weight = input(op, "weight.changed", JsNumber(10), 1, 1000)
      val scan = input(op, "datamatrix.detected", JsString("DM-C"), 2, 1100)
      val first = new StockInputs(actor)(system, ec)
      assert(Await.result(first.submit(weight), 5.seconds) == Right(false))
      testKit.stop(actor)
      val restarted = spawn(InventoryActor(new FileInventoryStore(file)))
      val pipeline = new StockInputs(restarted)(system, ec)
      assert(Await.result(pipeline.submit(scan), 5.seconds) == Right(true))
      assert(Await.result(pipeline.submit(scan.copy(receivedAt = 9999)), 5.seconds) == Right(true))
      val probe = createTestProbe[Inventory]()
      restarted ! InventoryActor.Get(probe.ref)
      val state = probe.receiveMessage()
      assert(state.batches.head.quantity == 41 && state.movements.size == 1)
      testKit.stop(restarted)
      Files.delete(file); Files.delete(directory)
    }
    "confirmer un retrait avec le moteur local sans service externe" in {
      val store = new InventoryStore {
        def load(): Inventory = initial; def save(s: Inventory): Unit = ()
      }
      val actor = spawn(InventoryActor(store))
      val pipeline = new StockInputs(actor)(system, ec)
      val op = UUID.randomUUID().toString
      assert(
        Await.result(
          pipeline.submit(input(op, "datamatrix.detected", JsString("DM-C"), 1, 0)),
          5.seconds
        ) == Right(false)
      )
      val result =
        Await.result(pipeline.submit(input(op, "weight.changed", JsNumber(-10), 2, 100)), 5.seconds)
      assert(result == Right(true))
      val probe = createTestProbe[Inventory]()
      actor ! InventoryActor.Get(probe.ref)
      assert(probe.receiveMessage().batches.head.quantity == 39)
    }
    "confirmer 40 vers39 en Scala puis persister une seule fois" in {
      val store = new InventoryStore {
        def load(): Inventory = initial; def save(s: Inventory): Unit = ()
      }
      val actor = spawn(InventoryActor(store))
      val pipeline = new StockInputs(actor)(system, ec)
      val op = UUID.randomUUID().toString
      val scan = input(op, "datamatrix.detected", JsString("DM-C"), 1, 0)
      val weight = input(op, "weight.changed", JsNumber(-10), 2, 100)
      locally {
        assert(Await.result(pipeline.submit(scan), 5.seconds) == Right(false))
        assert(Await.result(pipeline.submit(weight), 10.seconds) == Right(true))
        assert(Await.result(pipeline.submit(weight), 5.seconds) == Right(true))
        val probe = createTestProbe[Inventory]()
        actor ! InventoryActor.Get(probe.ref)
        val state = probe.receiveMessage()
        assert(state.batches.head.quantity == 39 && state.movements.size == 1)
      }
    }
  }
}
