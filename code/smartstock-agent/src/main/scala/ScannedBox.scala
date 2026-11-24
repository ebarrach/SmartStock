
package smartstock

import java.time.LocalDate

case class ScannedBox(
  gtin: String,
  serial: String,
  lot: String,
  expiration: LocalDate
) {
  def batchId: String = s"$gtin-$lot"
  def serialKey: (String, String) = (gtin, serial)
}

object ScannedBox {

  def fromJson(json: ujson.Value): ScannedBox =
    ScannedBox(
      json("gtin").str,
      json("serial").str,
      json("lot").str,
      LocalDate.parse(json("expiration").str)
    )
}
