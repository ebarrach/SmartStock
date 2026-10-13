
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

  // Simuler une mesure du capteur
  final case class MeasureTemperature(
    value: Double,
    replyTo: ActorRef[TemperatureResult]
  ) extends Command

  // Simuler une panne
  final case class SimulateFailure(
    replyTo: ActorRef[TemperatureResult]
  ) extends Command

  // Simuler le rétablissement du capteur
  final case class RecoverSensor(
    replyTo: ActorRef[TemperatureResult]
  ) extends Command

  def apply(): Behavior[Command] = {

    val minTemperature = 15.0
    val maxTemperature = 25.0

    def behavior(sensorWorking: Boolean): Behavior[Command] =

      Behaviors.receiveMessage {

        case MeasureTemperature(value, replyTo) =>

          if (!sensorWorking) {
            replyTo ! SensorFailure(
              "Capteur de température indisponible"
            )

          } else if (value.isNaN || value.isInfinity) {
            replyTo ! SensorFailure(
              "Mesure de température invalide"
            )

          } else if (value < minTemperature) {
            replyTo ! TemperatureAlert(
              value,
              "Température trop basse"
            )

          } else if (value > maxTemperature) {
            replyTo ! TemperatureAlert(
              value,
              "Température trop élevée"
            )

          } else {
            replyTo ! TemperatureNormal(value)
          }

          Behaviors.same

        case SimulateFailure(replyTo) =>
          replyTo ! SensorFailure(
            "Panne du capteur de température"
          )

          behavior(false)

        case RecoverSensor(replyTo) =>
          replyTo ! TemperatureNormal(
            Double.NaN
          )

          behavior(true)
      }

    behavior(true)
  }
}
