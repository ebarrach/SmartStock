
package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

object FailureActor {

  sealed trait Command

  final case class ComponentFailed(
    component: String
  ) extends Command

  final case class ComponentRecovered(
    component: String
  ) extends Command

  final case class GetFailures(
    replyTo: ActorRef[Set[String]]
  ) extends Command

  def apply(): Behavior[Command] = {

    def behavior(
      failedComponents: Set[String]
    ): Behavior[Command] =

      Behaviors.receiveMessage {

        case ComponentFailed(component) =>
          println(s"PANNE détectée : $component")
          behavior(failedComponents + component)

        case ComponentRecovered(component) =>
          println(s"Composant rétabli : $component")
          behavior(failedComponents - component)

        case GetFailures(replyTo) =>
          replyTo ! failedComponents
          Behaviors.same
      }

    behavior(Set.empty[String])
  }
}
