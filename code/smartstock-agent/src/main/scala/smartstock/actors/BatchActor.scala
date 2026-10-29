package smartstock.actors
/*This file manages batches of medications, their quantities, expiration dates, and movement history
 */
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import smartstock.model.MedicationBatch
import java.time.LocalDate

object BatchActor {

  sealed trait Command
  sealed trait BatchResult

  final case class BatchAdded(
      batch: MedicationBatch
  ) extends BatchResult

  final case class BatchRejected(
      reason: String
  ) extends BatchResult

  final case class BatchUpdated(
      batch: MedicationBatch
  ) extends BatchResult

  final case class AddBatch(
      batch: MedicationBatch,
      replyTo: ActorRef[BatchResult]
  ) extends Command

  final case class AddToBatch(
      batchId: String,
      quantity: Int,
      replyTo: ActorRef[BatchResult]
  ) extends Command

  final case class RemoveFromBatch(
      batchId: String,
      quantity: Int,
      replyTo: ActorRef[BatchResult]
  ) extends Command

  final case class FindBatch(
      batchId: String,
      replyTo: ActorRef[Option[MedicationBatch]]
  ) extends Command

  final case class GetBatchesForMedication(
      medicationId: String,
      replyTo: ActorRef[Vector[MedicationBatch]]
  ) extends Command

  final case class GetTotalStock(
      medicationId: String,
      replyTo: ActorRef[Int]
  ) extends Command

  // NOUVEAU : récupérer le stock de tous les médicaments
  final case class GetAllStock(
      replyTo: ActorRef[Map[String, Int]]
  ) extends Command

  final case class GetExpirationAlerts(
      today: LocalDate,
      thresholdDays: Int,
      replyTo: ActorRef[Vector[MedicationBatch]]
  ) extends Command

  final case class BatchMovement(
      batchId: String,
      quantity: Int,
      movementType: String
  )

  final case class GetBatchHistory(
      replyTo: ActorRef[Vector[BatchMovement]]
  ) extends Command

  def apply(): Behavior[Command] = {

    def behavior(
        batches: Map[String, MedicationBatch],
        history: Vector[BatchMovement]
    ): Behavior[Command] =

      Behaviors.receiveMessage {

        case AddBatch(batch, replyTo) =>

          if (
            batch.batchId.trim.isEmpty ||
            batch.medicationId.trim.isEmpty ||
            batch.quantity <= 0
          ) {
            replyTo ! BatchRejected(
              "Informations du lot invalides"
            )
            Behaviors.same

          } else if (batches.contains(batch.batchId)) {
            replyTo ! BatchRejected(
              "Identifiant de lot déjà enregistré"
            )
            Behaviors.same

          } else {
            val updated = batches.updated(
              batch.batchId,
              batch
            )

            replyTo ! BatchAdded(batch)
            behavior(updated, history)
          }

        case AddToBatch(batchId, quantity, replyTo) =>

          batches.get(batchId) match {

            case None =>
              replyTo ! BatchRejected("Lot introuvable")
              Behaviors.same

            case Some(batch) =>

              if (quantity <= 0) {
                replyTo ! BatchRejected(
                  "La quantité doit être positive"
                )
                Behaviors.same

              } else if (quantity > Int.MaxValue - batch.quantity) {
                replyTo ! BatchRejected(
                  "Quantité trop importante"
                )
                Behaviors.same

              } else {

                val updatedBatch = batch.copy(
                  quantity = batch.quantity + quantity
                )

                val updatedBatches = batches.updated(
                  batchId,
                  updatedBatch
                )

                val movement = BatchMovement(
                  batchId,
                  quantity,
                  "ADD"
                )

                val updatedHistory = history :+ movement

                replyTo ! BatchUpdated(updatedBatch)

                behavior(updatedBatches, updatedHistory)
              }
          }

        case RemoveFromBatch(batchId, quantity, replyTo) =>

          batches.get(batchId) match {

            case None =>
              replyTo ! BatchRejected("Lot introuvable")
              Behaviors.same

            case Some(batch) =>

              if (quantity <= 0) {
                replyTo ! BatchRejected(
                  "La quantité doit être positive"
                )
                Behaviors.same

              } else if (quantity > batch.quantity) {
                replyTo ! BatchRejected(
                  "Stock insuffisant dans le lot"
                )
                Behaviors.same

              } else {

                val updatedBatch = batch.copy(
                  quantity = batch.quantity - quantity
                )

                val updatedBatches = batches.updated(
                  batchId,
                  updatedBatch
                )

                val movement = BatchMovement(
                  batchId,
                  quantity,
                  "REMOVE"
                )

                val updatedHistory = history :+ movement

                replyTo ! BatchUpdated(updatedBatch)

                behavior(updatedBatches, updatedHistory)
              }
          }

        case FindBatch(batchId, replyTo) =>
          replyTo ! batches.get(batchId)
          Behaviors.same

        case GetBatchesForMedication(medicationId, replyTo) =>

          val result = batches.values.toVector
            .filter(_.medicationId == medicationId)
            .sortBy(_.batchId)

          replyTo ! result
          Behaviors.same

        case GetTotalStock(medicationId, replyTo) =>

          val total = batches.values.toVector
            .filter(_.medicationId == medicationId)
            .map(_.quantity)
            .sum

          replyTo ! total
          Behaviors.same

        // NOUVEAU : regrouper les lots par médicament
        case GetAllStock(replyTo) =>

          val grouped = batches.values.toVector
            .groupBy(_.medicationId)

          val totals = grouped.map { case (medicationId, medicationBatches) =>
            medicationId -> medicationBatches
              .map(_.quantity)
              .sum
          }

          replyTo ! totals
          Behaviors.same

        case GetExpirationAlerts(today, thresholdDays, replyTo) =>

          val alerts = batches.values.toVector
            .filter { batch =>
              batch.quantity > 0 &&
              (
                batch.isExpired(today) ||
                  batch.isExpiringSoon(today, thresholdDays)
              )
            }
            .sortBy(_.expirationDate.toEpochDay)

          replyTo ! alerts
          Behaviors.same

        case GetBatchHistory(replyTo) =>
          replyTo ! history
          Behaviors.same
      }

    behavior(
      Map.empty[String, MedicationBatch],
      Vector.empty[BatchMovement]
    )
  }
}
