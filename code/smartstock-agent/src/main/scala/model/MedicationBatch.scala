package smartstock.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit

final case class MedicationBatch(
  batchId: String,
  medicationId: String,
  quantity: Int,
  expirationDate: LocalDate
) {

  def daysUntilExpiration(today: LocalDate): Long =
    ChronoUnit.DAYS.between(today, expirationDate)

  def isExpired(today: LocalDate): Boolean =
    daysUntilExpiration(today) < 0

  def isExpiringSoon(
    today: LocalDate,
    thresholdDays: Int = 30
  ): Boolean = {
    val days = daysUntilExpiration(today)
    days >= 0 && days <= thresholdDays
  }
}
