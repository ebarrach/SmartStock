package smartstock.inventory

import java.time.{Clock, Instant, LocalDate}
import java.util.UUID
import scala.util.Try
import spray.json._

final case class Product(
    id: String,
    name: String,
    dataMatrix: String,
    unitWeightGrams: Double,
    minStock: Int
)
final case class Batch(
    id: String,
    productId: String,
    location: String,
    quantity: Int,
    expiresOn: String,
    lotCode: Option[String] = None,
    serialInventoryStatus: String = "unclassified",
    traceabilityNote: String = ""
)
final case class Movement(
    id: String,
    batchId: String,
    delta: Int,
    quantityAfter: Int,
    occurredAt: String,
    eventId: String,
    productId: String = "",
    kind: String = "legacy",
    source: String = "unknown",
    reason: String = "",
    serial: Option[String] = None,
    initialPresenceUnknown: Boolean = false
)
final case class Reading(kind: String, value: Double, source: String, observedAt: String)
final case class SensorRecord(
    id: String,
    operationId: String,
    source: String,
    batchId: String,
    kind: String,
    value: JsValue,
    sequence: Long,
    receivedAt: Long
)
final case class StockBaseline(quantity: Int, recordedAt: String)
final case class Inventory(
    products: Vector[Product] = Vector.empty,
    batches: Vector[Batch] = Vector.empty,
    movements: Vector[Movement] = Vector.empty,
    readings: Vector[Reading] = Vector.empty,
    receipts: Map[String, String] = Map.empty,
    sensorInputs: Vector[SensorRecord] = Vector.empty,
    baselines: Map[String, StockBaseline] = Map.empty,
    dataset: String = "real",
    dailyMeasures: Vector[DailyMeasure] = Vector.empty
)
final case class DomainError(code: String, message: String, status: Int = 400)
final case class EnvironmentLimits(
    temperatureMin: Double = 15,
    temperatureMax: Double = 25,
    humidityMin: Double = 30,
    humidityMax: Double = 60
) {
  require(Vector(temperatureMin, temperatureMax, humidityMin, humidityMax).forall(_.isFinite))
  require(
    temperatureMin < temperatureMax && 0 <= humidityMin && humidityMin < humidityMax && humidityMax <= 100
  )
}
final case class Alert(code: String, subjectId: String, message: String)

sealed trait InventoryOperation { def fingerprint: String }
final case class RegisterProduct(product: Product) extends InventoryOperation {
  def fingerprint: String = { import InventoryJson._; product.toJson.compactPrint }
}
final case class RegisterBatch(batch: Batch) extends InventoryOperation {
  def fingerprint: String = {
    import InventoryJson._
    val fields = batch.toJson.asJsObject.fields
    JsObject(
      if (
        batch.lotCode.isEmpty && batch.serialInventoryStatus == "unclassified" && batch.traceabilityNote.isEmpty
      )
        fields -- Set("lotCode", "serialInventoryStatus", "traceabilityNote")
      else fields
    ).compactPrint
  }
}
final case class ConfigureBatchTraceability(
    batchId: String,
    lotCode: String,
    status: String,
    note: String
) extends InventoryOperation {
  def fingerprint: String =
    JsArray(JsString(batchId), JsString(lotCode), JsString(status), JsString(note)).compactPrint
}
final case class AdjustStock(
    batchId: String,
    delta: Int,
    expectedBefore: Option[Int] = None,
    kind: String = "correction",
    source: String = "manual",
    reason: String = "",
    serial: Option[String] = None
) extends InventoryOperation {
  def fingerprint: String = {
    val base = JsObject(
      "batchId" -> JsString(batchId),
      "delta" -> JsNumber(delta),
      "expectedBefore" -> expectedBefore.map(JsNumber(_)).getOrElse(JsNull)
    )
    if (kind == "correction" && source == "manual" && reason.isEmpty && serial.isEmpty)
      base.compactPrint
    else
      JsObject(
        base.fields ++ Map(
          "kind" -> JsString(kind),
          "source" -> JsString(source),
          "reason" -> JsString(reason)
        ) ++ serial.map(s => "serial" -> JsString(s))
      ).compactPrint
  }
}
final case class RecordReading(reading: Reading, sequence: Option[Long] = None)
    extends InventoryOperation {
  def fingerprint: String = {
    import InventoryJson._; reading.toJson.compactPrint + ":" + sequence.toString
  }
}

final case class RecordSensor(input: SensorRecord) extends InventoryOperation {
  def fingerprint: String = {
    import InventoryJson._; input.copy(receivedAt = 0).toJson.compactPrint
  }
}

object InventoryJson extends DefaultJsonProtocol {
  implicit val productFormat: RootJsonFormat[Product] = jsonFormat5(Product.apply)
  private val fullBatchFormat = jsonFormat8(Batch.apply)
  implicit val batchFormat: RootJsonFormat[Batch] = new RootJsonFormat[Batch] {
    def write(batch: Batch): JsValue = fullBatchFormat.write(batch)
    def read(value: JsValue): Batch = fullBatchFormat.read(
      JsObject(
        Map(
          "lotCode" -> JsNull,
          "serialInventoryStatus" -> JsString("unclassified"),
          "traceabilityNote" -> JsString("")
        ) ++ value.asJsObject.fields
      )
    )
  }
  private val fullMovementFormat = jsonFormat12(Movement.apply)
  implicit val movementFormat: RootJsonFormat[Movement] = new RootJsonFormat[Movement] {
    def write(value: Movement): JsValue = fullMovementFormat.write(value)
    def read(value: JsValue): Movement = fullMovementFormat.read(
      JsObject(
        Map(
          "productId" -> JsString(""),
          "kind" -> JsString("legacy"),
          "source" -> JsString("unknown"),
          "reason" -> JsString(""),
          "serial" -> JsNull,
          "initialPresenceUnknown" -> JsBoolean(false)
        ) ++ value.asJsObject.fields
      )
    )
  }
  implicit val baselineFormat: RootJsonFormat[StockBaseline] = jsonFormat2(StockBaseline.apply)
  implicit val readingFormat: RootJsonFormat[Reading] = jsonFormat4(Reading.apply)
  implicit val sensorFormat: RootJsonFormat[SensorRecord] = jsonFormat8(SensorRecord.apply)
  implicit val dailyMeasureFormat: RootJsonFormat[DailyMeasure] = jsonFormat7(DailyMeasure.apply)
  private val fullInventoryFormat = jsonFormat9(Inventory.apply)
  implicit val inventoryFormat: RootJsonFormat[Inventory] = new RootJsonFormat[Inventory] {
    def write(state: Inventory): JsValue = fullInventoryFormat.write(state)
    def read(value: JsValue): Inventory = {
      val original = value.asJsObject.fields
      val fields =
        if (original.contains("dailyMeasures")) original
        else
          original.updated(
            "dailyMeasures",
            MeasureHistory.from(original("readings").convertTo[Vector[Reading]]).toJson
          )
      fullInventoryFormat.read(
        JsObject(
          Map(
            "sensorInputs" -> JsArray(),
            "baselines" -> JsObject(),
            "dataset" -> JsString("legacy")
          ) ++ fields
        )
      )
    }
  }
  implicit val alertFormat: RootJsonFormat[Alert] = jsonFormat3(Alert.apply)
}

/** Pure stock rules. The actor coordinates IO; it does not implement these rules. */
object InventoryRules {
  private def validTraceability(batch: Batch): Boolean =
    Set("unclassified", "historical-uninventoried", "inventoried")(batch.serialInventoryStatus) &&
      batch.lotCode.forall(s => s.nonEmpty && s.length <= 20 && !s.exists(_.isControl)) &&
      batch.traceabilityNote.length <= 500 &&
      (batch.serialInventoryStatus != "historical-uninventoried" ||
        (batch.lotCode.nonEmpty && batch.traceabilityNote.trim.nonEmpty))
  private def validId(value: String): Boolean = value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
  private def invalid(message: String) = Left(DomainError("invalid_input", message))

  def execute(
      state: Inventory,
      operation: InventoryOperation,
      eventId: String,
      clock: Clock
  ): Either[DomainError, Inventory] = {
    val fingerprint = operation.getClass.getSimpleName + ":" + operation.fingerprint
    if (Try(UUID.fromString(eventId)).toOption.forall(_.toString != eventId.toLowerCase))
      return invalid("UUID de commande requis")
    state.receipts.get(eventId) match {
      case Some(previous) if previous == fingerprint => return Right(state)
      case Some(_) => return Left(DomainError("id_conflict", "Identifiant reutilise", 409))
      case None    => ()
    }
    if (state.movements.exists(_.eventId == eventId))
      return Left(DomainError("id_conflict", "Identifiant historique deja utilise", 409))
    val streamKey = operation match {
      case RecordSensor(input) => Some(s"stream:${input.source}:${input.sequence}")
      case RecordReading(reading, Some(sequence)) => Some(s"stream:${reading.source}:$sequence")
      case _                                      => None
    }
    if (streamKey.exists(key => state.receipts.get(key).exists(_ != eventId)))
      return Left(DomainError("sequence_conflict", "Sequence deja utilisee par la source", 409))
    val result: Either[DomainError, Inventory] = operation match {
      case RegisterProduct(product) =>
        if (
          !validId(product.id) || product.name.trim.isEmpty || product.name.length > 200 ||
          product.dataMatrix.trim.isEmpty || product.dataMatrix.length > 512 ||
          !product.unitWeightGrams.isFinite || product.unitWeightGrams <= 0 || product.minStock < 0
        )
          invalid("Produit invalide")
        else if (
          state.products.exists(p => p.id == product.id || p.dataMatrix == product.dataMatrix)
        )
          Left(DomainError("product_exists", "Produit ou DataMatrix deja enregistre", 409))
        else Right(state.copy(products = state.products :+ product))
      case RegisterBatch(batch) =>
        if (
          !validId(batch.id) || batch.quantity < 0 ||
          !Set("cabinet", "bag").contains(batch.location) ||
          Try(LocalDate.parse(batch.expiresOn)).isFailure || !validTraceability(batch)
        )
          invalid("Lot invalide")
        else if (!state.products.exists(_.id == batch.productId))
          Left(DomainError("product_not_found", "Produit inconnu", 404))
        else if (state.batches.exists(_.id == batch.id))
          Left(DomainError("batch_exists", "Lot deja enregistre", 409))
        else
          Right(
            state.copy(
              batches = state.batches :+ batch,
              baselines = state.baselines
                .updated(batch.id, StockBaseline(batch.quantity, Instant.now(clock).toString))
            )
          )
      case ConfigureBatchTraceability(batchId, lotCode, status, note) =>
        state.batches
          .find(_.id == batchId)
          .toRight(DomainError("batch_not_found", "Lot inconnu", 404))
          .flatMap { batch =>
            val updated = batch.copy(
              lotCode = Some(lotCode),
              serialInventoryStatus = status,
              traceabilityNote = note
            )
            if (!validTraceability(updated) || note.trim.isEmpty)
              invalid("Classification explicite, lot et justification requis")
            else if (
              batch.lotCode.exists(_ != lotCode) && state.movements
                .exists(m => m.batchId == batchId && m.serial.nonEmpty)
            )
              Left(
                DomainError(
                  "serial_batch_conflict",
                  "Lot physique deja utilise par des series",
                  409
                )
              )
            else
              Right(
                state.copy(batches = state.batches.map(b => if (b.id == batchId) updated else b))
              )
          }
      case AdjustStock(batchId, delta, expectedBefore, kind, source, reason, serial) =>
        state.batches
          .find(_.id == batchId)
          .toRight(DomainError("batch_not_found", "Lot inconnu", 404))
          .flatMap { batch =>
            val next = batch.quantity.toLong + delta.toLong
            val previousUnit = serial.flatMap(s =>
              state.movements.reverseIterator
                .find(m => m.productId == batch.productId && m.serial.contains(s))
            )
            if (
              !Set("consumption", "replenishment", "correction", "loss", "expiry")(
                kind
              ) || !validId(source) || reason.length > 500 ||
              (Set("consumption", "loss", "expiry")(
                kind
              ) && delta >= 0) || (kind == "replenishment" && delta <= 0)
            ) invalid("Type, origine ou signe de mouvement invalide")
            else if (
              serial.exists(s => s.isEmpty || s.length > 20 || s.exists(_.isControl)) ||
              (serial.nonEmpty && math.abs(delta.toLong) != 1)
            ) invalid("Une serie identifie exactement une unite")
            else if (
              serial.nonEmpty && (batch.lotCode.isEmpty || state.batches.count(b =>
                b.productId == batch.productId && b.location == batch.location && b.expiresOn == batch.expiresOn &&
                  (b.lotCode == batch.lotCode || b.id == state.products
                    .find(_.id == batch.productId)
                    .get
                    .dataMatrix + "-" + batch.lotCode.get)
              ) != 1)
            )
              Left(DomainError("ambiguous_batch", "Lot physique absent ou ambigu", 409))
            else if (
              previousUnit.exists(m => (m.delta > 0 && delta > 0) || (m.delta < 0 && delta < 0))
            )
              Left(DomainError("serial_state_conflict", "Serie deja presente ou deja retiree", 409))
            else if (previousUnit.exists(_.batchId != batchId))
              Left(DomainError("serial_batch_conflict", "Serie presente dans un autre lot", 409))
            else if (
              serial.nonEmpty && delta < 0 && previousUnit.isEmpty && batch.serialInventoryStatus != "historical-uninventoried"
            )
              Left(
                DomainError(
                  "unknown_serial",
                  "Serie inconnue : lot non declare historique a series non inventoriees",
                  409
                )
              )
            else if (expectedBefore.exists(_ != batch.quantity))
              Left(DomainError("stock_changed", "Stock modifie pendant confirmation", 409))
            else if (delta == 0) invalid("Variation nulle")
            else if (next < 0 || next > Int.MaxValue)
              Left(DomainError("stock_conflict", "Stock insuffisant ou depassement", 409))
            else {
              val movement = Movement(
                UUID.randomUUID().toString,
                batchId,
                delta,
                next.toInt,
                Instant.now(clock).toString,
                eventId,
                batch.productId,
                kind,
                source,
                if (serial.nonEmpty && previousUnit.isEmpty && delta < 0)
                  "présence initiale inconnue — retrait validé"
                else reason,
                serial,
                serial.nonEmpty && previousUnit.isEmpty && delta < 0
              )
              Right(
                state.copy(
                  batches = state.batches
                    .map(b => if (b.id == batchId) b.copy(quantity = next.toInt) else b),
                  movements = state.movements :+ movement
                )
              )
            }
          }
      case RecordSensor(input) =>
        if (
          !state.batches
            .exists(_.id == input.batchId) || Try(UUID.fromString(input.operationId)).isFailure ||
          !validId(input.source) || input.sequence < 0 || !Set(
            "datamatrix.detected",
            "weight.changed"
          )(input.kind)
        )
          invalid("Observation de stock invalide")
        else Right(state.copy(sensorInputs = state.sensorInputs :+ input))
      case RecordReading(reading, _) =>
        val supported = Set(
          "door.changed",
          "weight.raw",
          "weight.delta.raw",
          "temperature.measured",
          "humidity.measured",
          "infrared.detected",
          "fan.changed",
          "connection.changed",
          "sensor.failed"
        )
        if (
          !supported(reading.kind) || !reading.value.isFinite || !validId(reading.source) ||
          Try(Instant.parse(reading.observedAt)).isFailure ||
          (reading.kind == "humidity.measured" && (reading.value < 0 || reading.value > 100)) ||
          (reading.kind == "fan.changed" && (reading.value < 0 || reading.value > 100)) ||
          (Set("infrared.detected", "connection.changed", "sensor.failed", "door.changed")(
            reading.kind
          ) && !Set(
            0.0,
            1.0
          )(reading.value))
        )
          invalid("Mesure invalide")
        else {
          val appended = state.readings :+ reading
          val readings = if (reading.kind == "temperature.measured") {
            val previous = state.readings.reverseIterator
              .find(_.kind == "fan.changed")
              .map(_.value)
              .getOrElse(0.0)
            val power = smartstock.engine.ThermalPolicy.next(reading.value, previous)
            appended :+ Reading("fan.changed", power, "scala-engine", reading.observedAt)
          } else appended
          Right(
            state.copy(
              readings = readings.takeRight(1000),
              dailyMeasures = MeasureHistory.record(
                if (state.dailyMeasures.nonEmpty) state.dailyMeasures
                else MeasureHistory.from(state.readings),
                reading
              )
            )
          )
        }
    }
    result.map { updated =>
      val receipts = updated.receipts.updated(eventId, fingerprint)
      updated.copy(receipts =
        streamKey.map(key => receipts.updated(key, eventId)).getOrElse(receipts)
      )
    }
  }

  def alerts(
      state: Inventory,
      clock: Clock,
      limits: EnvironmentLimits = EnvironmentLimits()
  ): Vector[Alert] = {
    val today = LocalDate.now(clock)
    val stock = state.products.flatMap { product =>
      val batches = state.batches.filter(_.productId == product.id)
      val locations = if (batches.isEmpty) Vector("cabinet") else batches.map(_.location).distinct
      locations.flatMap { location =>
        val quantity = batches.filter(_.location == location).map(_.quantity.toLong).sum
        Option.when(quantity < product.minStock)(
          Alert("low_stock", s"${product.id}:$location", s"$location: $quantity")
        )
      }
    }
    val expirations = state.batches.filter(_.quantity > 0).flatMap { batch =>
      val expiry = LocalDate.parse(batch.expiresOn)
      if (expiry.isBefore(today)) Some(Alert("expired", batch.id, batch.expiresOn))
      else
        Option.when(!expiry.isAfter(today.plusDays(30)))(
          Alert("expiring_soon", batch.id, batch.expiresOn)
        )
    }
    val environment =
      state.readings.groupBy(r => r.source -> r.kind).values.toVector.flatMap { readings =>
        val r = readings.last
        val code = r.kind match {
          case "temperature.measured"
              if r.value < limits.temperatureMin || r.value > limits.temperatureMax =>
            Some("temperature_alert")
          case "humidity.measured"
              if r.value < limits.humidityMin || r.value > limits.humidityMax =>
            Some("humidity_alert")
          case "sensor.failed" if r.value == 1      => Some("sensor_failure")
          case "connection.changed" if r.value == 0 => Some("connection_lost")
          case "door.changed" if r.value == 1 && {
                val opened = readings.reverseIterator.takeWhile(_.value == 1).toVector.last
                !Instant.parse(opened.observedAt).plusSeconds(60).isAfter(clock.instant())
              } =>
            Some("door_open")
          case _ => None
        }
        code.map(c => Alert(c, r.source, s"${r.kind}: ${r.value}"))
      }
    stock ++ expirations ++ environment
  }
}
