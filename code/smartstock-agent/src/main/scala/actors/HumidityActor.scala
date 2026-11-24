package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

/** Derniere humidite + evaluation des seuils existants. */
object HumidityActor {
  sealed trait Command
  sealed trait HumidityResult
  case class HumidityNormal(value: Double) extends HumidityResult
  case class HumidityAlert(value: Double, message: String) extends HumidityResult
  case class SensorFailure(message: String) extends HumidityResult
  case class MeasureHumidity(value: Double, replyTo: ActorRef[HumidityResult]) extends Command
  case class GetStatus(replyTo: ActorRef[Option[HumidityResult]]) extends Command

  def apply(): Behavior[Command] = running(None)

  private def running(latest: Option[HumidityResult]): Behavior[Command] =
    Behaviors.receiveMessage {
      case MeasureHumidity(v, reply) =>
        val result: HumidityResult =
          if (v.isNaN || v.isInfinity || v < 0 || v > 100) SensorFailure("Humidite invalide")
          else if (v < 5.0) HumidityAlert(v, "Humidite trop basse")
          else if (v > 10.0) HumidityAlert(v, "Humidite trop elevee")
          else HumidityNormal(v)
        reply ! result
        running(Some(result))
      case GetStatus(reply) =>
        reply ! latest
        Behaviors.same
    }
}
