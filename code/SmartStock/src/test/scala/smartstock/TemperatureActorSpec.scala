
package smartstock

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

import org.scalatest.wordspec.AnyWordSpecLike

import smartstock.actors.TemperatureActor

class TemperatureActorSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike {

  "TemperatureActor" should {

    "accepter une température normale" in {

      val actor = spawn(TemperatureActor())

      val probe =
        createTestProbe[TemperatureActor.TemperatureResult]()

      actor ! TemperatureActor.MeasureTemperature(
        20.0,
        probe.ref
      )

      probe.expectMessage(
        TemperatureActor.TemperatureNormal(20.0)
      )
    }

    "détecter une température trop élevée" in {

      val actor = spawn(TemperatureActor())

      val probe =
        createTestProbe[TemperatureActor.TemperatureResult]()

      actor ! TemperatureActor.MeasureTemperature(
        28.0,
        probe.ref
      )

      probe.expectMessage(
        TemperatureActor.TemperatureAlert(
          28.0,
          "Température trop élevée"
        )
      )
    }

    "détecter une température trop basse" in {

      val actor = spawn(TemperatureActor())

      val probe =
        createTestProbe[TemperatureActor.TemperatureResult]()

      actor ! TemperatureActor.MeasureTemperature(
        10.0,
        probe.ref
      )

      probe.expectMessage(
        TemperatureActor.TemperatureAlert(
          10.0,
          "Température trop basse"
        )
      )
    }

    "refuser les mesures pendant une panne" in {

      val actor = spawn(TemperatureActor())

      val probe =
        createTestProbe[TemperatureActor.TemperatureResult]()

      actor ! TemperatureActor.SimulateFailure(
        probe.ref
      )

      probe.expectMessage(
        TemperatureActor.SensorFailure(
          "Panne du capteur de température"
        )
      )

      actor ! TemperatureActor.MeasureTemperature(
        20.0,
        probe.ref
      )

      probe.expectMessage(
        TemperatureActor.SensorFailure(
          "Capteur de température indisponible"
        )
      )
    }

    "reprendre les mesures après rétablissement" in {

      val actor = spawn(TemperatureActor())

      val probe =
        createTestProbe[TemperatureActor.TemperatureResult]()

      actor ! TemperatureActor.SimulateFailure(
        probe.ref
      )

      probe.expectMessage(
        TemperatureActor.SensorFailure(
          "Panne du capteur de température"
        )
      )

      actor ! TemperatureActor.RecoverSensor(
        probe.ref
      )

      // Le code actuel utilise NaN pour signaler
      // le rétablissement du capteur.
      val recovery = probe.receiveMessage()

      assert(
        recovery match {
          case TemperatureActor.TemperatureNormal(value) =>
            value.isNaN

          case _ =>
            false
        }
      )

      actor ! TemperatureActor.MeasureTemperature(
        22.0,
        probe.ref
      )

      probe.expectMessage(
        TemperatureActor.TemperatureNormal(22.0)
      )
    }
  }
}
