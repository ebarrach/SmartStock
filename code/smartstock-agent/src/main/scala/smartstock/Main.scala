package smartstock

import akka.actor.typed.{ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import smartstock.actors.EventRouter
import smartstock.model._
import java.time.Instant
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration._

object Main {
  def main(args: Array[String]): Unit = {
    val system = ActorSystem(demo, "SmartStock")
    // Only the command-line entrypoint waits; actor handlers never block.
    Await.ready(system.whenTerminated, 10.seconds)
  }

  def demo: Behavior[EventRouter.Result] =
    Behaviors.setup { context =>
      val sink = context.spawn(
        Behaviors.setup[EventRouter.Delivery] { sinkContext =>
          Behaviors.receiveMessage { delivery =>
            sinkContext.log.info("Simulation : evenement transmis {}", delivery.envelope.id)
            delivery.replyTo ! EventRouter.Accepted(delivery.envelope.id)
            Behaviors.same
          }
        },
        "simulated-destination"
      )
      val router = context.spawn(EventRouter(sink, sink, sink), "router")
      router ! EventRouter.Forward(
        EventEnvelope(
          UUID.randomUUID(),
          "cabinet-01",
          1,
          Instant.now(),
          DataMatrixDetected("compresses-demo")
        ),
        context.self
      )
      Behaviors.receiveMessage { result =>
        context.log.info("Resultat simulation : {}", result)
        Behaviors.stopped
      }
    }
}
