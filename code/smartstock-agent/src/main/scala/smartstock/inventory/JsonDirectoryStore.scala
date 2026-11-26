package smartstock.inventory

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.security.MessageDigest
import java.util.UUID
import scala.util.control.NonFatal
import spray.json._
import InventoryJson._

/** Single InventoryActor writer. WAL + atomic commit marker fences the multi-file transaction. On
  * restart, an uncommitted journal is undone; a committed journal is redone before reading.
  */
final class JsonDirectoryStore(
    val directory: Path,
    allowExamples: Boolean = false,
    stage: String => Unit = _ => ()
) extends InventoryStore {
  private val journal = directory.resolve("transaction.json")
  private val marker = directory.resolve("commit.json")
  private var poisoned = false
  private var loaded = false
  private var current: Option[Inventory] = None
  val files: Vector[String] = Vector(
    "products.json",
    "batches.json",
    "units.json",
    "movements.json",
    "settings.json",
    "calibration.json",
    "receipts.json",
    "sensors.json",
    "metadata.json"
  )
  private def hash(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 255}%02x").mkString
  private def read(path: Path): JsValue = Files.readString(path, UTF_8).parseJson
  private def atomic(path: Path, value: JsValue): Unit = {
    Files.createDirectories(directory)
    val temp = Files.createTempFile(directory, ".transaction-", ".tmp")
    try {
      val channel = FileChannel.open(temp, StandardOpenOption.WRITE)
      try {
        val bytes = ByteBuffer.wrap((value.prettyPrint + "\n").getBytes(UTF_8))
        while (bytes.hasRemaining) channel.write(bytes)
        channel.force(true)
      } finally channel.close()
      Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally Files.deleteIfExists(temp)
  }
  private def serials(state: Inventory): JsValue = {
    val last = state.movements
      .filter(_.serial.nonEmpty)
      .groupBy(m => m.productId -> m.serial.get)
      .toVector
      .sortBy(_._1)
      .map { case ((product, serial), movements) =>
        val m = movements.last
        JsObject(
          "productId" -> JsString(product),
          "serial" -> JsString(serial),
          "batchId" -> JsString(m.batchId),
          "present" -> JsBoolean(m.delta > 0),
          "movementId" -> JsString(m.id),
          "eventId" -> JsString(m.eventId),
          "recordedAt" -> JsString(m.occurredAt),
          "source" -> JsString(m.source),
          "initialPresenceUnknown" -> JsBoolean(m.initialPresenceUnknown)
        )
      }
    JsArray(last)
  }
  private def sections(state: Inventory): Map[String, JsValue] = {
    val config = state.serverSettings.getOrElse(ServerSettings())
    Map(
      "products.json" -> state.products.toJson,
      "batches.json" -> state.batches.toJson,
      "units.json" -> serials(state),
      "movements.json" -> state.movements.toJson,
      "settings.json" -> JsObject(
        "configured" -> JsBoolean(state.serverSettings.nonEmpty),
        "withoutSensors" -> JsBoolean(config.withoutSensors),
        "modeEpochMillis" -> JsNumber(config.modeEpochMillis),
        "archivedProducts" -> config.archivedProducts.toJson
      ),
      "calibration.json" -> JsObject(
        "tare" -> config.tare.toJson,
        "calibration" -> config.calibration.toJson
      ),
      "receipts.json" -> state.receipts.toJson,
      "sensors.json" -> JsObject(
        "readings" -> state.readings.toJson,
        "sensorInputs" -> state.sensorInputs.toJson,
        "dailyMeasures" -> state.dailyMeasures.toJson
      ),
      "metadata.json" -> JsObject(
        "schemaVersion" -> JsNumber(1),
        "dataset" -> JsString(state.dataset),
        "baselines" -> state.baselines.toJson
      )
    )
  }
  private def writeSections(state: Inventory, hooks: Boolean): Unit = {
    val data = sections(state)
    files.foreach { name =>
      atomic(directory.resolve(name), data(name))
      if (hooks) stage("file:" + name)
    }
  }
  private def commit(id: String): JsObject = JsObject(
    "schemaVersion" -> JsNumber(1),
    "transactionId" -> JsString(id),
    "sha256" -> JsObject(
      files.map(name => name -> JsString(hash(Files.readAllBytes(directory.resolve(name))))).toMap
    )
  )
  private def committed(id: String): Boolean = Files.exists(marker) &&
    read(marker).asJsObject.fields("transactionId") == JsString(id)

  private def recover(): Unit = if (Files.exists(journal)) {
    stage("recover")
    val record = read(journal).asJsObject.fields
    require(record("schemaVersion") == JsNumber(1), "Version du journal inconnue")
    val id = record("transactionId").convertTo[String]
    if (committed(id)) {
      val after = record("after").convertTo[Inventory]
      InventoryValidation.validate(after, allowExamples)
      writeSections(after, hooks = false)
      atomic(marker, commit(id))
    } else
      record("before") match {
        case JsNull =>
          files.foreach(name => Files.deleteIfExists(directory.resolve(name)));
          Files.deleteIfExists(marker)
        case before =>
          val state = before.convertTo[Inventory]
          InventoryValidation.validate(state, allowExamples)
          writeSections(state, hooks = false)
          atomic(marker, commit(record("previousId").convertTo[String]))
      }
    Files.deleteIfExists(journal)
  }

  def load(): Inventory = synchronized {
    require(!poisoned, "Stockage en erreur : redemarrage requis")
    recover()
    val state = if (!Files.exists(marker)) {
      require(
        !Vector("inventory.json", "stock.json", "serials.json", "history.json").exists(name =>
          Files.exists(directory.resolve(name))
        ),
        "Ancien stockage dans ce dossier : sauvegarde et migration explicites requises"
      )
      require(
        !files.exists(name => Files.exists(directory.resolve(name))),
        "JSON existants sans commit : migration explicite requise, aucun fichier ecrase"
      )
      None
    } else {
      val manifest = read(marker).asJsObject.fields
      require(manifest("schemaVersion") == JsNumber(1), "Version du stockage inconnue")
      val hashes = manifest("sha256").asJsObject.fields
      require(hashes.keySet == files.toSet, "Manifest incomplet")
      files.foreach(name =>
        require(
          hashes(name) == JsString(hash(Files.readAllBytes(directory.resolve(name)))),
          s"JSON incoherent ou modifie hors transaction : $name"
        )
      )
      val data = files.map(name => name -> read(directory.resolve(name))).toMap
      val meta = data("metadata.json").asJsObject.fields
      val sensors = data("sensors.json").asJsObject.fields
      val settings = data("settings.json").asJsObject.fields
      val calibration = data("calibration.json").asJsObject.fields
      require(meta("schemaVersion") == JsNumber(1), "Version metadata inconnue")
      val config =
        if (settings("configured") == JsBoolean(false)) None
        else
          Some(
            ServerSettings(
              settings("withoutSensors").convertTo[Boolean],
              calibration("tare").convertTo[Option[TareCapture]],
              calibration("calibration").convertTo[Option[ScaleCalibration]],
              settings("archivedProducts").convertTo[Vector[String]],
              settings("modeEpochMillis").convertTo[Long]
            )
          )
      val inventory = Inventory(
        data("products.json").convertTo[Vector[Product]],
        data("batches.json").convertTo[Vector[Batch]],
        data("movements.json").convertTo[Vector[Movement]],
        sensors("readings").convertTo[Vector[Reading]],
        data("receipts.json").convertTo[Map[String, String]],
        sensors("sensorInputs").convertTo[Vector[SensorRecord]],
        meta("baselines").convertTo[Map[String, StockBaseline]],
        meta("dataset").convertTo[String],
        sensors("dailyMeasures").convertTo[Vector[DailyMeasure]],
        config
      )
      InventoryValidation.validate(inventory, allowExamples)
      require(
        data("units.json") == serials(inventory),
        "Series incoherentes avec le journal des mouvements"
      )
      Some(inventory)
    }
    current = state; loaded = true
    state.getOrElse(Inventory())
  }

  def save(next: Inventory): Unit = synchronized {
    require(!poisoned, "Stockage en erreur : redemarrage requis")
    if (!loaded) load()
    if (current.contains(next)) return
    InventoryValidation.validate(next, allowExamples)
    val id = UUID.randomUUID().toString
    val previousId =
      if (Files.exists(marker)) read(marker).asJsObject.fields("transactionId") else JsString("")
    try {
      atomic(
        journal,
        JsObject(
          "schemaVersion" -> JsNumber(1),
          "transactionId" -> JsString(id),
          "previousId" -> previousId,
          "before" -> current.map(_.toJson).getOrElse(JsNull),
          "after" -> next.toJson
        )
      )
      stage("prepared")
      writeSections(next, hooks = true)
      stage("before-commit")
      atomic(marker, commit(id)) // Commit point: every dedicated JSON has been forced to disk.
      stage("committed")
      current = Some(next)
      try Files.deleteIfExists(journal)
      catch { case NonFatal(_) => () }
    } catch {
      case NonFatal(error) =>
        val durable =
          try committed(id)
          catch { case NonFatal(failure) => poisoned = true; throw failure }
        try recover()
        catch { case NonFatal(failure) => poisoned = true; throw failure }
        if (durable) current = Some(next) else throw error
    }
  }
}
