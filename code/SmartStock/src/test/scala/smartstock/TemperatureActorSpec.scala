
package smartstock

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

import smartstock.actors.TemperatureActor

class TemperatureActorSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike {

  "TemperatureActor" should {

    "accepter une temperature normale" in {

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

    "detecter une temperature trop elevee" in {

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
          "Temperature trop elevee"
        )
      )
    }

    "detecter une temperature trop basse" in {

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
          "Temperature trop basse"
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
          "Panne du capteur de temperature"
        )
      )

      actor ! TemperatureActor.MeasureTemperature(
        20.0,
        probe.ref
      )

      probe.expectMessage(
        TemperatureActor.SensorFailure(
          "Capteur de temperature indisponible"
        )
      )
    }

    "reprendre les mesures apres retablissement" in {

      val actor = spawn(TemperatureActor())

      val probe =
        createTestProbe[TemperatureActor.TemperatureResult]()

      actor ! TemperatureActor.SimulateFailure(
        probe.ref
      )

      probe.expectMessage(
        TemperatureActor.SensorFailure(
          "Panne du capteur de temperature"
        )
      )

      actor ! TemperatureActor.RecoverSensor(
        probe.ref
      )

      probe.expectMessage(
        TemperatureActor.SensorRecovered(
          "Capteur de temperature retabli"
        )
      )

      actor ! TemperatureActor.MeasureTemperature(
        22.0,
        probe.ref
      )

      probe.expectMessage(
        TemperatureActor.TemperatureNormal(22.0)
      )
    }

    "permettre de modifier les seuils" in {

      val actor = spawn(TemperatureActor())

      val probe =
        createTestProbe[TemperatureActor.TemperatureResult]()

      actor ! TemperatureActor.SetThresholds(
        18.0,
        24.0,
        probe.ref
      )

      probe.expectMessage(
        TemperatureActor.Thresholds(18.0, 24.0)
      )

      actor ! TemperatureActor.MeasureTemperature(
        25.0,
        probe.ref
      )

      probe.expectMessage(
        TemperatureActor.TemperatureAlert(
          25.0,
          "Temperature trop elevee"
        )
      )
    }

    "permettre de consulter les seuils" in {

      val actor = spawn(TemperatureActor())

      val probe =
        createTestProbe[TemperatureActor.TemperatureResult]()

      actor ! TemperatureActor.GetThresholds(
        probe.ref
      )

      probe.expectMessage(
        TemperatureActor.Thresholds(15.0, 25.0)
      )
    }

    "refuser des seuils invalides" in {

      val actor = spawn(TemperatureActor())

      val probe =
        createTestProbe[TemperatureActor.TemperatureResult]()

      actor ! TemperatureActor.SetThresholds(
        30.0,
        10.0,
        probe.ref
      )

      probe.expectMessage(
        TemperatureActor.ThresholdsRejected(
          "Seuils invalides : min doit etre inferieur a max"
        )
      )

      actor ! TemperatureActor.GetThresholds(
        probe.ref
      )

      probe.expectMessage(
        TemperatureActor.Thresholds(15.0, 25.0)
      )
    }
  }
}
