package smartstock.events

import java.util.UUID
import smartstock.inventory.{Batch, Product}

sealed trait SensorInput {
  def id: UUID
  def operationId: UUID
  def source: String
  def batchId: String
}
final case class ScanInput(
    id: UUID,
    operationId: UUID,
    source: String,
    batchId: String,
    dataMatrix: String
) extends SensorInput
final case class WeightInput(
    id: UUID,
    operationId: UUID,
    source: String,
    batchId: String,
    grams: Double
) extends SensorInput
final case class StockCandidate(operationId: UUID, batchId: String, delta: Int)
final case class CorrelationError(code: String)
final case class Pending(scan: Option[ScanInput], weight: Option[WeightInput], receivedAt: Long)
final case class CorrelationState(
    pending: Map[(String, UUID, String), Pending] = Map.empty,
    seen: Map[UUID, (SensorInput, Long)] = Map.empty
)

/** Correlates observations only; a candidate is not an Anemone/business confirmation. */
final class Correlator(windowMillis: Long = 5000, toleranceGrams: Double = 0.5) {
  require(windowMillis > 0 && toleranceGrams.isFinite && toleranceGrams >= 0)
  def accept(
      state: CorrelationState,
      input: SensorInput,
      product: Product,
      batch: Batch,
      now: Long
  ): (CorrelationState, Either[CorrelationError, Option[StockCandidate]]) = {
    val cleaned = CorrelationState(
      state.pending.filter { case (_, p) => math.abs(now - p.receivedAt) <= windowMillis },
      state.seen.filter { case (_, (_, at)) => math.abs(now - at) <= windowMillis * 2 }
    )
    def reject(code: String) = cleaned -> Left(CorrelationError(code))
    cleaned.seen.get(input.id) match {
      case Some((previous, _)) if previous == input => return cleaned -> Right(None)
      case Some(_)                                  => return reject("event_id_conflict")
      case None                                     => ()
    }
    if (
      input.source.trim.isEmpty || input.batchId != batch.id || batch.productId != product.id ||
      !product.unitWeightGrams.isFinite || product.unitWeightGrams <= 0
    )
      return reject("invalid_reference")
    input match {
      case scan: ScanInput if scan.dataMatrix != product.dataMatrix =>
        return reject("unknown_datamatrix")
      case weight: WeightInput if !weight.grams.isFinite || weight.grams == 0 =>
        return reject("invalid_weight")
      case _ => ()
    }
    val key = (input.source, input.operationId, input.batchId)
    val pending = cleaned.pending.getOrElse(key, Pending(None, None, now))
    if (
      (input.isInstanceOf[ScanInput] && pending.scan.nonEmpty) ||
      (input.isInstanceOf[WeightInput] && pending.weight.nonEmpty)
    )
      return reject("ambiguous_operation")
    val next = input match {
      case scan: ScanInput     => pending.copy(scan = Some(scan))
      case weight: WeightInput => pending.copy(weight = Some(weight))
    }
    val seen = cleaned.seen.updated(input.id, input -> now)
    (next.scan, next.weight) match {
      case (Some(_), Some(weight)) =>
        val magnitude = math.abs(weight.grams) / product.unitWeightGrams
        val units = math.round(magnitude)
        val remaining = cleaned.copy(pending = cleaned.pending - key, seen = seen)
        if (
          units <= 0 || units > Int.MaxValue ||
          math.abs(math.abs(weight.grams) - units * product.unitWeightGrams) > toleranceGrams
        )
          remaining -> Left(CorrelationError("incoherent_weight"))
        else
          remaining -> Right(
            Some(
              StockCandidate(
                input.operationId,
                batch.id,
                if (weight.grams > 0) units.toInt else -units.toInt
              )
            )
          )
      case _ =>
        cleaned.copy(pending = cleaned.pending.updated(key, next), seen = seen) -> Right(None)
    }
  }
}
