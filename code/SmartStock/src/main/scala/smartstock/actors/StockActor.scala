
package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import smartstock.model.StockMovement
import java.time.Instant

object StockActor {

  sealed trait Command
  sealed trait StockResult

  final case class StockUpdated(
    medicationId: String,
    quantity: Int,
    currentStock: Int
  ) extends StockResult

  final case class StockRejected(
    medicationId: String,
    reason: String
  ) extends StockResult

  final case class AddStock(
    medicationId: String,
    quantity: Int,
    replyTo: ActorRef[StockResult]
  ) extends Command

  final case class RemoveStock(
    medicationId: String,
    quantity: Int,
    replyTo: ActorRef[StockResult]
  ) extends Command

  final case class ManualAddStock(
    medicationId: String,
    quantity: Int,
    replyTo: ActorRef[StockResult]
  ) extends Command

  final case class ManualRemoveStock(
    medicationId: String,
    quantity: Int,
    replyTo: ActorRef[StockResult]
  ) extends Command

  final case class GetStock(
    replyTo: ActorRef[Map[String, Int]]
  ) extends Command

  def apply(
    historyActor: ActorRef[HistoryActor.Command]
  ): Behavior[Command] = {

    def behavior(stock: Map[String, Int]): Behavior[Command] =
      Behaviors.receiveMessage {

        case AddStock(id, quantity, replyTo) =>
          updateStock(id, quantity, "ADD", replyTo, stock)

        case RemoveStock(id, quantity, replyTo) =>
          updateStock(id, quantity, "REMOVE", replyTo, stock)

        case ManualAddStock(id, quantity, replyTo) =>
          updateStock(id, quantity, "MANUAL_ADD", replyTo, stock)

        case ManualRemoveStock(id, quantity, replyTo) =>
          updateStock(id, quantity, "MANUAL_REMOVE", replyTo, stock)

        case GetStock(replyTo) =>
          replyTo ! stock
          Behaviors.same
      }

    def updateStock(
      id: String,
      quantity: Int,
      operation: String,
      replyTo: ActorRef[StockResult],
      stock: Map[String, Int]
    ): Behavior[Command] = {

      val current = stock.getOrElse(id, 0)
      val isRemoval = operation.endsWith("REMOVE")

      if (quantity <= 0) {
        replyTo ! StockRejected(
          id, "La quantité doit être positive"
        )
        Behaviors.same
      } else if (isRemoval && quantity > current) {
        replyTo ! StockRejected(
          id, "Stock insuffisant"
        )
        Behaviors.same
      } else {
        val newQuantity =
          if (isRemoval) current - quantity
          else current + quantity

        val updated = stock.updated(id, newQuantity)

        historyActor ! HistoryActor.AddMovement(
          StockMovement(id, quantity, operation, Instant.now())
        )

        replyTo ! StockUpdated(id, quantity, newQuantity)

        behavior(updated)
      }
    }

    behavior(Map.empty[String, Int])
  }
}
