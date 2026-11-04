package smartstock

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import smartstock.actors.EventRouter
import smartstock.model._
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

class EventRouterSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  def envelope(event: SmartStockEvent): EventEnvelope =
    EventEnvelope(UUID.randomUUID(), "cabinet-01", 1, Instant.EPOCH, event)

  "EventRouter" should {
    "router les categories vers leur destination sans modifier le message" in {
      val stock = createTestProbe[EventRouter.Delivery]()
      val environment = createTestProbe[EventRouter.Delivery]()
      val alerts = createTestProbe[EventRouter.Delivery]()
      val result = createTestProbe[EventRouter.Result]()
      val router = spawn(EventRouter(stock.ref, environment.ref, alerts.ref))
      Vector[(SmartStockEvent, akka.actor.testkit.typed.scaladsl.TestProbe[EventRouter.Delivery])](
        DataMatrixDetected("compresses") -> stock,
        WeightChanged(-10) -> stock,
        TemperatureMeasured(20) -> environment,
        HumidityMeasured(50) -> environment,
        InfraredDetected(true) -> environment,
        ComponentFailed("balance") -> alerts
      ).foreach { case (event, target) =>
        val env = envelope(event)
        router ! EventRouter.Forward(env, result.ref)
        val delivery = target.receiveMessage()
        assert(delivery.envelope == env)
        delivery.replyTo ! EventRouter.Accepted(env.id)
        result.expectMessage(EventRouter.Forwarded(env.id))
      }
    }

    "refuser les donnees invalides sans les transmettre" in {
      val target = createTestProbe[EventRouter.Delivery]()
      val result = createTestProbe[EventRouter.Result]()
      val router = spawn(EventRouter(target.ref, target.ref, target.ref))
      Vector(
        DataMatrixDetected(" "),
        WeightChanged(Double.NaN),
        TemperatureMeasured(Double.PositiveInfinity),
        HumidityMeasured(101)
      ).foreach { event =>
        val env = envelope(event)
        router ! EventRouter.Forward(env, result.ref)
        assert(result.receiveMessage().isInstanceOf[EventRouter.Rejected])
      }
      target.expectNoMessage(100.millis)
    }

    "correler les reponses concurrentes meme dans le desordre" in {
      val target = createTestProbe[EventRouter.Delivery]()
      val a = createTestProbe[EventRouter.Result]()
      val b = createTestProbe[EventRouter.Result]()
      val router = spawn(EventRouter(target.ref, target.ref, target.ref))
      val first = envelope(WeightChanged(-10))
      val second = envelope(WeightChanged(10))
      router ! EventRouter.Forward(first, a.ref)
      router ! EventRouter.Forward(second, b.ref)
      val deliveries = Vector(target.receiveMessage(), target.receiveMessage())
      deliveries.reverse.foreach(d => d.replyTo ! EventRouter.Accepted(d.envelope.id))
      a.expectMessage(EventRouter.Forwarded(first.id))
      b.expectMessage(EventRouter.Forwarded(second.id))
    }

    "signaler un delai puis accepter une nouvelle transmission" in {
      val target = createTestProbe[EventRouter.Delivery]()
      val result = createTestProbe[EventRouter.Result]()
      val router = spawn(EventRouter(target.ref, target.ref, target.ref, 150.millis))
      router ! EventRouter.Forward(envelope(WeightChanged(1)), result.ref)
      val late = target.receiveMessage()
      assert(result.receiveMessage().isInstanceOf[EventRouter.Rejected])
      late.replyTo ! EventRouter.Accepted(late.envelope.id)
      val next = envelope(WeightChanged(2))
      router ! EventRouter.Forward(next, result.ref)
      val delivery = target.receiveMessage()
      delivery.replyTo ! EventRouter.Accepted(next.id)
      result.expectMessage(EventRouter.Forwarded(next.id))
      testKit.stop(router)
    }

    "refuser un accuse portant un autre identifiant" in {
      val target = createTestProbe[EventRouter.Delivery]()
      val result = createTestProbe[EventRouter.Result]()
      val router = spawn(EventRouter(target.ref, target.ref, target.ref))
      router ! EventRouter.Forward(envelope(WeightChanged(1)), result.ref)
      target.receiveMessage().replyTo ! EventRouter.Accepted(UUID.randomUUID())
      assert(result.receiveMessage().isInstanceOf[EventRouter.Rejected])
    }
  }

  "EventEnvelope.parse" should {
    "composer les conversions et conserver les erreurs de frontiere" in {
      val id = UUID.randomUUID().toString
      assert(
        EventEnvelope
          .parse(id, "cabinet-01", "1", "2026-10-29T00:00:00Z", WeightChanged(1))
          .isSuccess
      )
      for (
        (badId, src, seq, ts) <- Vector(
          ("bad", "cabinet", "1", "2026-10-29T00:00:00Z"),
          (id, "", "1", "2026-10-29T00:00:00Z"),
          (id, "cabinet", "-1", "2026-10-29T00:00:00Z"),
          (id, "cabinet", "x", "2026-10-29T00:00:00Z"),
          (id, "cabinet", "1", "bad")
        )
      ) assert(EventEnvelope.parse(badId, src, seq, ts, WeightChanged(1)).isFailure)
    }
  }
}
