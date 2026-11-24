package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

/** Uniquement les capteurs actuellement indisponibles. */
object FailureActor {
  sealed trait Command
  case class SensorStatus(name: String, status: String, message: String) extends Command
  case class GetFailures(replyTo: ActorRef[Map[String, String]]) extends Command

  def apply(): Behavior[Command] = running(Map.empty)

  private def running(failures: Map[String, String]): Behavior[Command] =
    Behaviors.receiveMessage {
      case SensorStatus(name, "disconnected", message) =>
        running(failures.updated(name, message))
      case SensorStatus(name, "connected" | "alive", _) =>
        running(failures - name)
      case SensorStatus(_, _, _) => Behaviors.same
      case GetFailures(reply) =>
        reply ! failures
        Behaviors.same
    }
}
