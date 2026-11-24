package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration._

/** Valide les mouvements et evite les doublons MQTT rapproches. */
object WeightActor {
  sealed trait Command
  case class Movement(direction: String, value: Long, delta: Long) extends Command

  def apply(workflow: ActorRef[StockWorkflowActor.Command]): Behavior[Command] = {
    def running(last: Option[(String, Long, Long, Long)]): Behavior[Command] =
      Behaviors.receiveMessage {
        case Movement(direction, value, delta) =>
          val valid = (direction == "entry" && delta > 0) ||
            (direction == "exit" && delta < 0)
          val now = System.nanoTime()
          val duplicate = last.exists { case (d, v, n, time) =>
            d == direction && v == value && n == delta && now - time < 2.seconds.toNanos
          }
          if (!valid || duplicate) Behaviors.same
          else {
            workflow ! StockWorkflowActor.WeightMovement(direction)
            running(Some((direction, value, delta, now)))
          }
      }
    running(None)
  }
}
