package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

object InfraredActor {

  sealed trait Command

  sealed trait InfraredResult

  final case class ObjectDetected(
      message: String
  ) extends InfraredResult

  final case class NoObjectDetected(
      message: String
  ) extends InfraredResult

  final case class SensorFailure(
      message: String
  ) extends InfraredResult

  final case class SensorRecovered(
      message: String
  ) extends InfraredResult

  // Recevoir une mesure du capteur infrarouge
  final case class DetectObject(
      detected: Boolean,
      replyTo: ActorRef[InfraredResult]
  ) extends Command

  // Simuler une panne
  final case class SimulateFailure(
      replyTo: ActorRef[InfraredResult]
  ) extends Command

  // Simuler le retablissement
  final case class RecoverSensor(
      replyTo: ActorRef[InfraredResult]
  ) extends Command

  def apply(): Behavior[Command] = {

    def behavior(
        sensorWorking: Boolean
    ): Behavior[Command] = {

      Behaviors.receiveMessage {

        case DetectObject(detected, replyTo) =>

          if (!sensorWorking) {
            replyTo ! SensorFailure(
              "Capteur infrarouge indisponible"
            )
          } else if (detected) {
            replyTo ! ObjectDetected(
              "Objet detecte devant le capteur"
            )
          } else {
            replyTo ! NoObjectDetected(
              "Aucun objet detecte"
            )
          }

          Behaviors.same

        case SimulateFailure(replyTo) =>

          replyTo ! SensorFailure(
            "Panne du capteur infrarouge"
          )

          behavior(false)

        case RecoverSensor(replyTo) =>

          replyTo ! SensorRecovered(
            "Capteur infrarouge retabli"
          )

          behavior(true)
      }
    }

    behavior(sensorWorking = true)
  }
}
