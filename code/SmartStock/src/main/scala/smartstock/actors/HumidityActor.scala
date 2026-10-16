
package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

object HumidityActor {

  sealed trait Command
  sealed trait HumidityResult

  final case class HumidityNormal(
    value: Double
  ) extends HumidityResult

  final case class HumidityAlert(
    value: Double,
    message: String
  ) extends HumidityResult

  final case class SensorFailure(
    message: String
  ) extends HumidityResult

  final case class SensorRecovered(
    message: String
  ) extends HumidityResult

  final case class Thresholds(
    min: Double,
    max: Double
  ) extends HumidityResult

  final case class ThresholdsRejected(
    message: String
  ) extends HumidityResult

  final case class MeasureHumidity(
    value: Double,
    replyTo: ActorRef[HumidityResult]
  ) extends Command

  final case class SetThresholds(
    min: Double,
    max: Double,
    replyTo: ActorRef[HumidityResult]
  ) extends Command

  final case class GetThresholds(
    replyTo: ActorRef[HumidityResult]
  ) extends Command

  final case class SimulateFailure(
    replyTo: ActorRef[HumidityResult]
  ) extends Command

  final case class RecoverSensor(
    replyTo: ActorRef[HumidityResult]
  ) extends Command

  def apply(): Behavior[Command] = {

    def behavior(
      sensorWorking: Boolean,
      minHumidity: Double,
      maxHumidity: Double
    ): Behavior[Command] = {

      Behaviors.receiveMessage {

        case MeasureHumidity(value, replyTo) =>

          if (!sensorWorking) {
            replyTo ! SensorFailure(
              "Capteur d'humidite indisponible"
            )
          } else if (
            value.isNaN ||
            value.isInfinity ||
            value < 0.0 ||
            value > 100.0
          ) {
            replyTo ! SensorFailure(
              "Mesure d'humidite invalide"
            )
          } else if (value < minHumidity) {
            replyTo ! HumidityAlert(
              value,
              "Humidite trop basse"
            )
          } else if (value > maxHumidity) {
            replyTo ! HumidityAlert(
              value,
              "Humidite trop elevee"
            )
          } else {
            replyTo ! HumidityNormal(value)
          }

          Behaviors.same

        case SetThresholds(min, max, replyTo) =>

          if (
            min.isNaN ||
            min.isInfinity ||
            max.isNaN ||
            max.isInfinity ||
            min < 0.0 ||
            max > 100.0 ||
            min >= max
          ) {
            replyTo ! ThresholdsRejected(
              "Seuils invalides : 0 <= min < max <= 100"
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
            minHumidity,
            maxHumidity
          )

          Behaviors.same

        case SimulateFailure(replyTo) =>

          replyTo ! SensorFailure(
            "Panne du capteur d'humidite"
          )

          behavior(
            false,
            minHumidity,
            maxHumidity
          )

        case RecoverSensor(replyTo) =>

          replyTo ! SensorRecovered(
            "Capteur d'humidite retabli"
          )

          behavior(
            true,
            minHumidity,
            maxHumidity
          )
      }
    }

    behavior(
      sensorWorking = true,
      minHumidity = 30.0,
      maxHumidity = 60.0
    )
  }
}
