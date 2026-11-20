package smartstock.inventory

import java.time.{Instant, ZoneOffset}

final case class DailyMeasure(
    day: String,
    kind: String,
    source: String,
    count: Long,
    total: BigDecimal,
    min: Double,
    max: Double
)
object MeasureHistory {
  def record(history: Vector[DailyMeasure], reading: Reading): Vector[DailyMeasure] = {
    if (!Set("temperature.measured", "humidity.measured")(reading.kind)) history
    else {
      val day = Instant.parse(reading.observedAt).atZone(ZoneOffset.UTC).toLocalDate.toString
      val index = history.indexWhere(d =>
        d.day == day && d.kind == reading.kind && d.source == reading.source
      )
      if (index < 0)
        history :+ DailyMeasure(
          day,
          reading.kind,
          reading.source,
          1,
          BigDecimal.decimal(reading.value),
          reading.value,
          reading.value
        )
      else {
        val previous = history(index)
        history.updated(
          index,
          previous.copy(
            count = previous.count + 1,
            total = previous.total + BigDecimal.decimal(reading.value),
            min = previous.min.min(reading.value),
            max = previous.max.max(reading.value)
          )
        )
      }
    }
  }
  def from(readings: Vector[Reading]): Vector[DailyMeasure] =
    readings.foldLeft(Vector.empty[DailyMeasure])(record)
}
