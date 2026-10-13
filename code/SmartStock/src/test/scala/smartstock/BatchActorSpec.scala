
package smartstock

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

import org.scalatest.wordspec.AnyWordSpecLike

import smartstock.actors.BatchActor
import smartstock.model.MedicationBatch

import java.time.LocalDate

class BatchActorSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike {

  "BatchActor" should {

    "enregistrer un lot correctement" in {

      val actor = spawn(BatchActor())

      val probe =
        createTestProbe[BatchActor.BatchResult]()

      val batch = MedicationBatch(
        "LOT-A",
        "MED001",
        6,
        LocalDate.now().plusDays(30)
      )

      actor ! BatchActor.AddBatch(
        batch,
        probe.ref
      )

      probe.expectMessage(
        BatchActor.BatchAdded(batch)
      )
    }

    "refuser un retrait supérieur au stock" in {

      val actor = spawn(BatchActor())

      val probe =
        createTestProbe[BatchActor.BatchResult]()

      val batch = MedicationBatch(
        "LOT-B",
        "MED001",
        4,
        LocalDate.now().plusDays(30)
      )

      actor ! BatchActor.AddBatch(
        batch,
        probe.ref
      )

      probe.expectMessage(
        BatchActor.BatchAdded(batch)
      )

      actor ! BatchActor.RemoveFromBatch(
        "LOT-B",
        10,
        probe.ref
      )

      probe.expectMessage(
        BatchActor.BatchRejected(
          "Stock insuffisant dans le lot"
        )
      )
    }

    "calculer le stock total de plusieurs lots" in {

      val actor = spawn(BatchActor())

      val batchProbe =
        createTestProbe[BatchActor.BatchResult]()

      val totalProbe =
        createTestProbe[Int]()

      val today = LocalDate.now()

      val batchA = MedicationBatch(
        "LOT-A",
        "MED001",
        6,
        today.plusDays(30)
      )

      val batchB = MedicationBatch(
        "LOT-B",
        "MED001",
        4,
        today.plusDays(60)
      )

      actor ! BatchActor.AddBatch(
        batchA,
        batchProbe.ref
      )

      batchProbe.expectMessage(
        BatchActor.BatchAdded(batchA)
      )

      actor ! BatchActor.AddBatch(
        batchB,
        batchProbe.ref
      )

      batchProbe.expectMessage(
        BatchActor.BatchAdded(batchB)
      )

      actor ! BatchActor.GetTotalStock(
        "MED001",
        totalProbe.ref
      )

      totalProbe.expectMessage(10)
    }

    "mettre à jour le stock après un retrait" in {

      val actor = spawn(BatchActor())

      val batchProbe =
        createTestProbe[BatchActor.BatchResult]()

      val totalProbe =
        createTestProbe[Int]()

      val batch = MedicationBatch(
        "LOT-A",
        "MED001",
        6,
        LocalDate.now().plusDays(30)
      )

      actor ! BatchActor.AddBatch(
        batch,
        batchProbe.ref
      )

      batchProbe.expectMessage(
        BatchActor.BatchAdded(batch)
      )

      actor ! BatchActor.RemoveFromBatch(
        "LOT-A",
        2,
        batchProbe.ref
      )

      batchProbe.expectMessage(
        BatchActor.BatchUpdated(
          batch.copy(quantity = 4)
        )
      )

      actor ! BatchActor.GetTotalStock(
        "MED001",
        totalProbe.ref
      )

      totalProbe.expectMessage(4)
    }
  }
}
