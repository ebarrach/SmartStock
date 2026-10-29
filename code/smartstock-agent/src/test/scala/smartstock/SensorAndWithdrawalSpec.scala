package smartstock

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import smartstock.actors.{BatchActor, HumidityActor, InfraredActor}
import smartstock.model.MedicationBatch
import java.time.LocalDate

class SensorAndWithdrawalSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  "Simulation de stock" should {
    "passer de 40 a 39 avec un mouvement et refuser un retrait excessif" in {
      val actor = spawn(BatchActor())
      val reply = createTestProbe[BatchActor.BatchResult]()
      val history = createTestProbe[Vector[BatchActor.BatchMovement]]()
      val batch = MedicationBatch("LOT-C", "compresses", 40, LocalDate.of(2027, 1, 1))
      actor ! BatchActor.AddBatch(batch, reply.ref)
      reply.expectMessage(BatchActor.BatchAdded(batch))
      actor ! BatchActor.RemoveFromBatch(batch.batchId, 1, reply.ref)
      reply.expectMessage(BatchActor.BatchUpdated(batch.copy(quantity = 39)))
      actor ! BatchActor.RemoveFromBatch(batch.batchId, 40, reply.ref)
      assert(reply.receiveMessage().isInstanceOf[BatchActor.BatchRejected])
      actor ! BatchActor.GetBatchHistory(history.ref)
      history.expectMessage(Vector(BatchActor.BatchMovement("LOT-C", 1, "REMOVE")))
    }
  }
  "HumidityActor" should {
    "refuser NaN et les valeurs hors limites puis recuperer apres panne" in {
      val actor = spawn(HumidityActor())
      val reply = createTestProbe[HumidityActor.HumidityResult]()
      Vector(Double.NaN, Double.PositiveInfinity, -1.0, 101.0).foreach { value =>
        actor ! HumidityActor.MeasureHumidity(value, reply.ref)
        assert(reply.receiveMessage().isInstanceOf[HumidityActor.SensorFailure])
      }
      actor ! HumidityActor.SimulateFailure(reply.ref)
      assert(reply.receiveMessage().isInstanceOf[HumidityActor.SensorFailure])
      actor ! HumidityActor.RecoverSensor(reply.ref)
      assert(reply.receiveMessage().isInstanceOf[HumidityActor.SensorRecovered])
      actor ! HumidityActor.MeasureHumidity(50, reply.ref)
      reply.expectMessage(HumidityActor.HumidityNormal(50))
    }
  }
  "InfraredActor" should {
    "distinguer presence absence et panne" in {
      val actor = spawn(InfraredActor())
      val reply = createTestProbe[InfraredActor.InfraredResult]()
      actor ! InfraredActor.DetectObject(true, reply.ref)
      assert(reply.receiveMessage().isInstanceOf[InfraredActor.ObjectDetected])
      actor ! InfraredActor.DetectObject(false, reply.ref)
      assert(reply.receiveMessage().isInstanceOf[InfraredActor.NoObjectDetected])
      actor ! InfraredActor.SimulateFailure(reply.ref)
      assert(reply.receiveMessage().isInstanceOf[InfraredActor.SensorFailure])
      actor ! InfraredActor.DetectObject(true, reply.ref)
      assert(reply.receiveMessage().isInstanceOf[InfraredActor.SensorFailure])
    }
  }
}
