
package smartstock.model

import java.time.LocalDate

final case class Medication(
  id: String,
  name: String,
  datamatrix: String,
  expirationDate: LocalDate,
  unitWeightGrams: Double
)
