package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout
import smartstock.model._
import java.util.UUID
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object EventRouter {
  sealed trait Command
  final case class Forward(envelope: EventEnvelope, replyTo: ActorRef[Result]) extends Command
  private final case class Completed(id: UUID, replyTo: ActorRef[Result], error: Option[String])
      extends Command
  final case class Delivery(envelope: EventEnvelope, replyTo: ActorRef[Accepted])
  final case class Accepted(id: UUID)
  sealed trait Result
  final case class Forwarded(id: UUID) extends Result
  final case class Rejected(id: UUID, reason: String) extends Result

  def apply(
      stock: ActorRef[Delivery],
      environment: ActorRef[Delivery],
      alerts: ActorRef[Delivery],
      responseTimeout: FiniteDuration = 2.seconds
  ): Behavior[Command] =
    Behaviors.setup { context =>
      import akka.actor.typed.scaladsl.AskPattern._
      implicit val timeout: Timeout = Timeout(responseTimeout)
      implicit val scheduler: akka.actor.typed.Scheduler = context.system.scheduler
      implicit val executionContext: scala.concurrent.ExecutionContext = context.executionContext

      Behaviors.receiveMessage {
        case Forward(envelope, replyTo) =>
          val destination = envelope.event match {
            case DataMatrixDetected(code) if code.trim.nonEmpty => Some(stock)
            case WeightChanged(grams) if grams.isFinite         => Some(stock)
            case TemperatureMeasured(value) if value.isFinite   => Some(environment)
            case HumidityMeasured(value) if value.isFinite && value >= 0 && value <= 100 =>
              Some(environment)
            case InfraredDetected(_)                                   => Some(environment)
            case ComponentFailed(component) if component.trim.nonEmpty => Some(alerts)
            case _                                                     => None
          }
          if (envelope.source.trim.isEmpty || envelope.sequence < 0) {
            replyTo ! Rejected(envelope.id, "Metadonnees invalides")
          } else
            destination match {
              case None => replyTo ! Rejected(envelope.id, "Evenement invalide")
              case Some(target) =>
                val delivery = target.ask[Accepted](ref => Delivery(envelope, ref)).map { ack =>
                  require(ack.id == envelope.id, "Accuse non correle")
                  ack
                }
                context.pipeToSelf(delivery) {
                  case Success(_) => Completed(envelope.id, replyTo, None)
                  case Failure(error) =>
                    Completed(envelope.id, replyTo, Some(error.getClass.getSimpleName))
                }
            }
          Behaviors.same
        case Completed(id, replyTo, None) =>
          replyTo ! Forwarded(id)
          Behaviors.same
        case Completed(id, replyTo, Some(error)) =>
          replyTo ! Rejected(id, error)
          Behaviors.same
      }
    }
}
