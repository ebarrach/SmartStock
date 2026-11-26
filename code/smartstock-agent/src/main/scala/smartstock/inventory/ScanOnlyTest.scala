package smartstock.inventory

import java.time.{Clock, Instant}

/** Explicit PC test profile, never a substitute for an uncalibrated connected scale. */
final case class ScanOnlySettings(enabled: Boolean = false, scaleDeclaredAbsent: Boolean = false) {
  def refusal(state: Inventory, clock: Clock): Option[DomainError] = {
    val freshWeight = state.readings.exists(r =>
      r.kind.startsWith("weight.") &&
        !Instant.parse(r.observedAt).isBefore(clock.instant().minusSeconds(60))
    ) || state.sensorInputs.exists(r =>
      r.kind == "weight.changed" && r.receivedAt >= clock.millis() - 60000
    )
    val connectedScale = state.readings
      .filter(r =>
        Set("sensor.failed", "connection.changed")(r.kind) &&
          Vector("weight", "hx711", "arduino", "scale", "balance", "poids").exists(
            r.source.toLowerCase.contains
          )
      )
      .groupBy(r => r.source -> r.kind)
      .values
      .exists(rs => if (rs.last.kind == "sensor.failed") rs.last.value == 0 else rs.last.value == 1)
    if (!enabled) Some(DomainError("scan_only_disabled", "Test sans balance desactive", 409))
    else if (!scaleDeclaredAbsent)
      Some(
        DomainError(
          "scale_absence_unconfirmed",
          "Profil PC : declarer explicitement la balance absente",
          409
        )
      )
    else if (freshWeight || connectedScale)
      Some(
        DomainError(
          "scale_connected",
          "Telemetrie de balance ou connexion detectee : utiliser le parcours normal scan/poids",
          409
        )
      )
    else None
  }
}
object ScanOnlySettings {
  def fromEnvironment: ScanOnlySettings = ScanOnlySettings(
    sys.env.get("SMARTSTOCK_SCAN_ONLY_TEST_MODE").contains("true"),
    sys.env.get("SMARTSTOCK_TEST_SCALE_ABSENT").contains("true")
  )
}
