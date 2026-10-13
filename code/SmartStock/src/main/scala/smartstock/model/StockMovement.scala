
package smartstock.model

import java.time.Instant

final case class StockMovement(
  medicationId: String,
  quantity: Int,
  movementType: String,
  timestamp: Instant
)
