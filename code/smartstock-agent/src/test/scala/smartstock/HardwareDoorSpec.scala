package smartstock

import org.scalatest.wordspec.AnyWordSpec
import smartstock.inventory._
import java.time.{Clock, Instant, ZoneOffset}

class HardwareDoorSpec extends AnyWordSpec {
  "La porte MQTT persistante" should {
    "conserver le debut d'ouverture malgre les repetitions et effacer l'alerte a la fermeture" in {
      val opened = Reading("door.changed", 1, "mkr", "2026-11-26T10:00:00Z")
      val repeated = opened.copy(observedAt = "2026-11-26T10:00:40Z")
      val state = Inventory(readings = Vector(opened, repeated))
      def alerts(s: Inventory, at: String) =
        InventoryRules.alerts(s, Clock.fixed(Instant.parse(at), ZoneOffset.UTC))
      assert(!alerts(state, "2026-11-26T10:00:59Z").exists(_.code == "door_open"))
      assert(alerts(state, "2026-11-26T10:01:00Z").count(_.code == "door_open") == 1)
      val closed = repeated.copy(value = 0, observedAt = "2026-11-26T10:01:01Z")
      assert(
        !alerts(state.copy(readings = state.readings :+ closed), "2026-11-26T10:02:00Z")
          .exists(_.code == "door_open")
      )
      val reopened = opened.copy(observedAt = "2026-11-26T10:02:00Z")
      assert(
        !alerts(
          state.copy(readings = state.readings ++ Vector(closed, reopened)),
          "2026-11-26T10:02:30Z"
        ).exists(_.code == "door_open")
      )
    }
  }
}
