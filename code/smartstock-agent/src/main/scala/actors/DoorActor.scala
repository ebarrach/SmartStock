package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration._

object DoorActor {
  sealed trait Command
  case class State(value: String) extends Command
  case class GetStatus(replyTo: ActorRef[DoorStatus]) extends Command
  private case object TooLong extends Command
  case class DoorStatus(open: Boolean, alert: Boolean)

  def apply(): Behavior[Command] = Behaviors.withTimers { timers =>
    def running(open: Boolean, alert: Boolean): Behavior[Command] =
      Behaviors.receiveMessage {
        case State("open") if !open =>
          timers.startSingleTimer(TooLong, 60.seconds)
          running(true, false)
        case State("closed") =>
          timers.cancel(TooLong)
          running(false, false)
        case TooLong if open => running(true, true)
        case GetStatus(reply) =>
          reply ! DoorStatus(open, alert)
          Behaviors.same
        case _ => Behaviors.same
      }
    running(false, false)
  }
}
