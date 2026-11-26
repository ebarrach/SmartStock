package smartstock.inventory

import java.time.{Clock, Instant}
import spray.json._

final case class TareCapture(source: String, raw: Double, capturedAt: String, id: String)
final case class ScaleCalibration(
    source: String,
    tareRaw: Double,
    loadedRaw: Double,
    referenceGrams: Double,
    measuredAt: String,
    id: String
) {
  def gramsPerRaw: Double = referenceGrams / (loadedRaw - tareRaw)
}
final case class ServerSettings(
    withoutSensors: Boolean = false,
    tare: Option[TareCapture] = None,
    calibration: Option[ScaleCalibration] = None,
    archivedProducts: Vector[String] = Vector.empty,
    modeEpochMillis: Long = 0
)
object ServerSettingsRules {
  def samples(
      state: Inventory,
      source: String,
      clock: Clock,
      after: Option[String] = None
  ): Vector[Reading] =
    state.readings
      .filter(r =>
        r.kind == "weight.raw" && r.source == source &&
          !Instant.parse(r.observedAt).isBefore(clock.instant().minusSeconds(10)) &&
          !Instant.parse(r.observedAt).isAfter(clock.instant()) &&
          after.forall(t => Instant.parse(r.observedAt).isAfter(Instant.parse(t)))
      )
      .takeRight(10)

  def execute(
      state: Inventory,
      operation: InventoryOperation,
      id: String,
      clock: Clock
  ): Either[DomainError, Inventory] = {
    val config = state.serverSettings.getOrElse(ServerSettings())
    def refuse(message: String) = Left(DomainError("calibration_unavailable", message, 409))
    operation match {
      case SetSensorMode(enabled) =>
        // Keep the durable inbox, but fence observations from another operating mode.
        Right(
          state.copy(serverSettings =
            Some(
              config.copy(
                withoutSensors = enabled,
                modeEpochMillis =
                  if (config.withoutSensors == enabled && state.serverSettings.nonEmpty)
                    config.modeEpochMillis
                  else clock.millis()
              )
            )
          )
        )
      case CaptureScaleTare(source) =>
        val values = samples(state, source, clock)
        if (values.size < 3)
          refuse("Balance non detectee : au moins trois mesures raw recentes requises")
        else
          Right(
            state.copy(serverSettings =
              Some(
                config.copy(tare =
                  Some(
                    TareCapture(
                      source,
                      values.map(_.value).sum / values.size,
                      clock.instant().toString,
                      id
                    )
                  )
                )
              )
            )
          )
      case CalibrateScale(source, grams, tareId) =>
        config.tare.filter(t => t.source == source && t.id == tareId) match {
          case None => refuse("Tare absente ou remplacee ; recommencer")
          case Some(tare) =>
            val values = samples(state, source, clock, Some(tare.capturedAt))
            val raw = if (values.isEmpty) tare.raw else values.map(_.value).sum / values.size
            val model = ScaleCalibration(source, tare.raw, raw, grams, clock.instant().toString, id)
            if (
              !grams.isFinite || grams <= 0 || values.size < 3 || raw == tare.raw ||
              !model.gramsPerRaw.isFinite || math.abs(model.gramsPerRaw) == 0
            )
              refuse("Masse positive et trois nouvelles mesures distinctes de la tare requises")
            else Right(state.copy(serverSettings = Some(config.copy(calibration = Some(model)))))
        }
      case ArchiveProduct(productId, gtin) =>
        state.products.find(_.id == productId) match {
          case Some(p) if p.dataMatrix == gtin =>
            Right(
              state.copy(serverSettings =
                Some(
                  config.copy(archivedProducts = (config.archivedProducts :+ productId).distinct)
                )
              )
            )
          case _ =>
            Left(DomainError("product_mismatch", "Identite du produit a archiver incoherente", 409))
        }
      case _ => Left(DomainError("invalid_input", "Configuration inconnue"))
    }
  }
}
