
package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

object TemperatureActor {

  sealed trait Command
  sealed trait TemperatureResult

  final case class TemperatureNormal(
    value: Double
  ) extends TemperatureResult

  final case class TemperatureAlert(
    value: Double,
    message: String
  ) extends TemperatureResult

  final case class SensorFailure(
    message: String
  ) extends TemperatureResult

  final case class SensorRecovered(
    message: String
  ) extends TemperatureResult

  final case class Thresholds(
    min: Double,
    max: Double
  ) extends TemperatureResult

  final case class ThresholdsRejected(
    message: String
  ) extends TemperatureResult

  final case class MeasureTemperature(
    value: Double,
    replyTo: ActorRef[TemperatureResult]
  ) extends Command

  final case class SetThresholds(
    min: Double,
    max: Double,
    replyTo: ActorRef[TemperatureResult]
  ) extends Command

  final case class GetThresholds(
    replyTo: ActorRef[TemperatureResult]
  ) extends Command

  final case class SimulateFailure(
    replyTo: ActorRef[TemperatureResult]
  ) extends Command

  final case class RecoverSensor(
    replyTo: ActorRef[TemperatureResult]
  ) extends Command

  def apply(): Behavior[Command] = {

    def behavior(
      sensorWorking: Boolean,
      minTemperature: Double,
      maxTemperature: Double
    ): Behavior[Command] = {

      Behaviors.receiveMessage {

        case MeasureTemperature(value, replyTo) =>

          if (!sensorWorking) {
            replyTo ! SensorFailure(
              "Capteur de temperature indisponible"
            )
          } else if (
            value.isNaN ||
            value.isInfinity
          ) {
            replyTo ! SensorFailure(
              "Mesure de temperature invalide"
            )
          } else if (value < minTemperature) {
            replyTo ! TemperatureAlert(
              value,
              "Temperature trop basse"
            )
          } else if (value > maxTemperature) {
            replyTo ! TemperatureAlert(
              value,
              "Temperature trop elevee"
            )
          } else {
            replyTo ! TemperatureNormal(value)
          }

          Behaviors.same

        case SetThresholds(min, max, replyTo) =>

          if (
            min.isNaN ||
            min.isInfinity ||
            max.isNaN ||
            max.isInfinity ||
            min >= max
          ) {
            replyTo ! ThresholdsRejected(
              "Seuils invalides : min doit etre inferieur a max"
            )

            Behaviors.same
          } else {
            replyTo ! Thresholds(min, max)

            behavior(
              sensorWorking,
              min,
              max
            )
          }

        case GetThresholds(replyTo) =>

          replyTo ! Thresholds(
            minTemperature,
            maxTemperature
          )

          Behaviors.same

        case SimulateFailure(replyTo) =>

          replyTo ! SensorFailure(
            "Panne du capteur de temperature"
          )

          behavior(
            false,
            minTemperature,
            maxTemperature
          )

        case RecoverSensor(replyTo) =>

          replyTo ! SensorRecovered(
            "Capteur de temperature retabli"
          )

          behavior(
            true,
            minTemperature,
            maxTemperature
          )
      }
    }

    behavior(
      sensorWorking = true,
      minTemperature = 15.0,
      maxTemperature = 25.0
    )
  }
}
