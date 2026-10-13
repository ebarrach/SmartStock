
package smartstock.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit

final case class MedicationBatch(
  batchId: String,
  medicationId: String,
  quantity: Int,
  expirationDate: LocalDate
) {

  // Nombre de jours avant la péremption
  def daysUntilExpiration(today: LocalDate): Long =
    ChronoUnit.DAYS.between(today, expirationDate)

  // Vérifier si le lot est déjà périmé
  def isExpired(today: LocalDate): Boolean =
    daysUntilExpiration(today) < 0

  // Vérifier si le lot expire bientôt
  def isExpiringSoon(
    today: LocalDate,
    thresholdDays: Int = 30
  ): Boolean = {
    val days = daysUntilExpiration(today)
    days >= 0 && days <= thresholdDays
  }
}
