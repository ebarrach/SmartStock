package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import smartstock.ScannedBox
import smartstock.model.MedicationBatch
import scala.concurrent.duration._

object StockWorkflowActor {
  sealed trait Command
  case class Scan(box: ScannedBox) extends Command
  case class WeightMovement(direction: String) extends Command
  private case object ScanExpired extends Command
  private case class BatchFound(batch: Option[MedicationBatch]) extends Command
  private case class BatchDone(result: BatchActor.BatchResult) extends Command
  private case class Operation(box: ScannedBox, entry: Boolean)

  def apply(batches: ActorRef[BatchActor.Command]): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        val foundReply = context.messageAdapter[Option[MedicationBatch]](BatchFound.apply)
        val doneReply = context.messageAdapter[BatchActor.BatchResult](BatchDone.apply)

        def running(
          scan: Option[ScannedBox],
          operation: Option[Operation],
          serials: Set[(String, String)]
        ): Behavior[Command] = Behaviors.receiveMessage {

          case Scan(box) if operation.isEmpty =>
            timers.startSingleTimer(ScanExpired, 90.seconds)
            running(Some(box), None, serials)

          case WeightMovement(direction) if scan.nonEmpty && operation.isEmpty &&
              (direction == "entry" || direction == "exit") =>
            val box = scan.get
            val entry = direction == "entry"
            timers.cancel(ScanExpired)
            if (serials.contains(box.serialKey) == entry)
              running(None, None, serials)
            else {
              batches ! BatchActor.FindBatch(box.batchId, foundReply)
              running(None, Some(Operation(box, entry)), serials)
            }

          case BatchFound(found) if operation.nonEmpty =>
            val op = operation.get
            found match {
              case Some(b) if b.medicationId != op.box.gtin || b.expirationDate != op.box.expiration =>
                running(None, None, serials)
              case None if op.entry =>
                batches ! BatchActor.AddBatch(
                  MedicationBatch(op.box.batchId, op.box.gtin, 1, op.box.expiration), doneReply)
                Behaviors.same
              case Some(_) if op.entry =>
                batches ! BatchActor.AddToBatch(op.box.batchId, 1, doneReply)
                Behaviors.same
              case Some(_) =>
                batches ! BatchActor.RemoveFromBatch(op.box.batchId, 1, doneReply)
                Behaviors.same
              case None => running(None, None, serials)
            }

          case BatchDone(result) if operation.nonEmpty =>
            val op = operation.get
            val success = result match {
              case _: BatchActor.BatchAdded   => true
              case _: BatchActor.BatchUpdated => true
              case _ => false
            }
            val next = if (!success) serials
              else if (op.entry) serials + op.box.serialKey
              else serials - op.box.serialKey
            running(None, None, next)

          case ScanExpired if operation.isEmpty => running(None, None, serials)
          case _ => Behaviors.same
        }
        running(None, None, Set.empty)
      }
    }
}
