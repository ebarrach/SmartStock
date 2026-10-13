
package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import smartstock.model.StockMovement

object HistoryActor {

  sealed trait Command

  final case class AddMovement(
    movement: StockMovement
  ) extends Command

  final case class GetHistory(
    replyTo: ActorRef[Vector[StockMovement]]
  ) extends Command

  def apply(): Behavior[Command] = {

    def behavior(
      history: Vector[StockMovement]
    ): Behavior[Command] =

      Behaviors.receiveMessage {

        case AddMovement(movement) =>
          val updated = history :+ movement
          behavior(updated)

        case GetHistory(replyTo) =>
          replyTo ! history
          Behaviors.same
      }

    behavior(Vector.empty)
  }
}
