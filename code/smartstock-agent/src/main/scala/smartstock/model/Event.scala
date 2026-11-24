package smartstock.model

import java.time.Instant
import java.util.UUID
import scala.util.Try

sealed trait SmartStockEvent
final case class DataMatrixDetected(code: String) extends SmartStockEvent
final case class WeightChanged(grams: Double) extends SmartStockEvent
final case class TemperatureMeasured(celsius: Double) extends SmartStockEvent
final case class HumidityMeasured(percent: Double) extends SmartStockEvent
final case class InfraredDetected(present: Boolean) extends SmartStockEvent
final case class ComponentFailed(component: String) extends SmartStockEvent

final case class EventEnvelope(
    id: UUID,
    source: String,
    sequence: Long,
    observedAt: Instant,
    event: SmartStockEvent
)

object EventEnvelope {
  // Boundary adapter for transport metadata; no stock logic belongs here.
  def parse(
      id: String,
      source: String,
      seq: String,
      ts: String,
      event: SmartStockEvent
  ): Try[EventEnvelope] = for {
    uuid <- Try(UUID.fromString(id))
    sequence <- Try(seq.toLong)
    timestamp <- Try(Instant.parse(ts))
    envelope <- Try {
      require(source.trim.nonEmpty, "source vide")
      require(sequence >= 0, "sequence negative")
      EventEnvelope(uuid, source, sequence, timestamp, event)
    }
  } yield envelope
}
