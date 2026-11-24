package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

/** Derniere temperature + evaluation des seuils. */
object TemperatureActor {
  sealed trait Command
  sealed trait TemperatureResult
  case class TemperatureNormal(value: Double) extends TemperatureResult
  case class TemperatureAlert(value: Double, message: String) extends TemperatureResult
  case class SensorFailure(message: String) extends TemperatureResult
  case class Thresholds(min: Double, max: Double) extends TemperatureResult
  case class ThresholdsRejected(message: String) extends TemperatureResult
  case class MeasureTemperature(value: Double, replyTo: ActorRef[TemperatureResult]) extends Command
  case class SetThresholds(min: Double, max: Double, replyTo: ActorRef[TemperatureResult]) extends Command
  case class GetStatus(replyTo: ActorRef[Option[TemperatureResult]]) extends Command

  def apply(): Behavior[Command] = running(15.0, 16.0, None)

  private def running(min: Double, max: Double, latest: Option[TemperatureResult]): Behavior[Command] =
    Behaviors.receiveMessage {
      case MeasureTemperature(v, reply) =>
        val result: TemperatureResult =
          if (v.isNaN || v.isInfinity) SensorFailure("Temperature invalide")
          else if (v < min) TemperatureAlert(v, "Temperature trop basse")
          else if (v > max) TemperatureAlert(v, "Temperature trop elevee")
          else TemperatureNormal(v)
        reply ! result
        running(min, max, Some(result))
      case SetThresholds(low, high, reply) =>
        if (low.isNaN || high.isNaN || low.isInfinity || high.isInfinity || low >= high) {
          reply ! ThresholdsRejected("Seuils invalides")
          Behaviors.same
        } else {
          reply ! Thresholds(low, high)
          running(low, high, latest)
        }
      case GetStatus(reply) =>
        reply ! latest
        Behaviors.same
    }
}
