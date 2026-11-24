package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import smartstock.model.MedicationBatch
import java.time.LocalDate

/** Inventaire courant en memoire, sans historique ni fichier. */
object BatchActor {
  sealed trait Command
  sealed trait BatchResult
  case class BatchAdded(batch: MedicationBatch) extends BatchResult
  case class BatchUpdated(batch: MedicationBatch) extends BatchResult
  case class BatchRejected(reason: String) extends BatchResult

  case class AddBatch(batch: MedicationBatch, replyTo: ActorRef[BatchResult]) extends Command
  case class AddToBatch(id: String, quantity: Int, replyTo: ActorRef[BatchResult]) extends Command
  case class RemoveFromBatch(id: String, quantity: Int, replyTo: ActorRef[BatchResult]) extends Command
  case class FindBatch(id: String, replyTo: ActorRef[Option[MedicationBatch]]) extends Command
  case class GetAllStock(replyTo: ActorRef[Map[String, Int]]) extends Command
  case class GetAllBatches(replyTo: ActorRef[Vector[MedicationBatch]]) extends Command
  case class GetExpirationAlerts(today: LocalDate, days: Int, replyTo: ActorRef[Vector[MedicationBatch]]) extends Command

  def apply(): Behavior[Command] = running(Map.empty)

  private def running(stock: Map[String, MedicationBatch]): Behavior[Command] =
    Behaviors.receiveMessage {
      case AddBatch(batch, reply) =>
        if (batch.batchId.trim.isEmpty || batch.medicationId.trim.isEmpty || batch.quantity <= 0 || stock.contains(batch.batchId)) {
          reply ! BatchRejected("Lot invalide ou deja present")
          Behaviors.same
        } else {
          reply ! BatchAdded(batch)
          running(stock.updated(batch.batchId, batch))
        }

      case AddToBatch(id, n, reply) =>
        stock.get(id) match {
          case Some(b) if n > 0 && n <= Int.MaxValue - b.quantity =>
            val updated = b.copy(quantity = b.quantity + n)
            reply ! BatchUpdated(updated)
            running(stock.updated(id, updated))
          case _ =>
            reply ! BatchRejected("Lot absent ou quantite invalide")
            Behaviors.same
        }

      case RemoveFromBatch(id, n, reply) =>
        stock.get(id) match {
          case Some(b) if n > 0 && n <= b.quantity =>
            val updated = b.copy(quantity = b.quantity - n)
            reply ! BatchUpdated(updated)
            running(stock.updated(id, updated))
          case _ =>
            reply ! BatchRejected("Lot absent ou stock insuffisant")
            Behaviors.same
        }

      case FindBatch(id, reply) =>
        reply ! stock.get(id)
        Behaviors.same
      case GetAllBatches(reply) =>
        reply ! stock.values.toVector.sortBy(_.batchId)
        Behaviors.same
      case GetAllStock(reply) =>
        reply ! stock.values.toVector.groupBy(_.medicationId).map {
          case (id, batches) => id -> batches.map(_.quantity).sum
        }
        Behaviors.same
      case GetExpirationAlerts(today, days, reply) =>
        reply ! stock.values.filter(b => b.quantity > 0 &&
          (b.isExpired(today) || b.isExpiringSoon(today, days.max(0)))).toVector.sortBy(_.expirationDate.toEpochDay)
        Behaviors.same
    }
}
