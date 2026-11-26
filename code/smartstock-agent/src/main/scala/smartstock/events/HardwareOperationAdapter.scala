package smartstock.events

import smartstock.inventory._
import smartstock.inventory.InventoryJson._
import spray.json._
import java.time.Instant
import java.util.UUID

/** Explicit ARM identities only. Never pairs the latest scan with an unlabelled weight. */
object HardwareOperationAdapter {
  def envelope(payload: String, state: Inventory): Either[String, Option[String]] = {
    if (state.serverSettings.exists(_.withoutSensors)) return Right(None)
    scala.util
      .Try {
        val data = payload.parseJson.asJsObject.fields
        val ids = Vector("operationId", "captureId").map(k =>
          data.get(k).collect { case JsString(v) if v.nonEmpty => v }
        )
        if (ids.forall(_.isEmpty) || !data.contains("rawDelta")) None
        else {
          require(ids.forall(_.nonEmpty), "Operation et capture partagees requises")
          val operation = UUID.fromString(ids(0).get).toString
          val capture = UUID.fromString(ids(1).get)
          require(capture.version() == 4, "Capture UUID v4 requise")
          val source = data("source").convertTo[String]
          val raw = data("rawDelta").convertTo[Double]
          require(raw.isFinite && raw != 0, "Variation brute native invalide")
          val scanSource = "capture-" + capture.toString.replace("-", "")
          val scans = state.sensorInputs.filter(r =>
            r.kind == "datamatrix.detected" &&
              r.operationId == operation && r.source == scanSource &&
              !state.serverSettings.exists(s =>
                s.modeEpochMillis > 0 && r.receivedAt <= s.modeEpochMillis
              )
          )
          require(scans.size == 1, "Scan partage absent ou ambigu : aucune association implicite")
          val scan = scans.head
          Some(
            JsObject(
              "v" -> JsNumber(1),
              "id" -> JsString(capture.toString),
              "type" -> JsString("weight.changed"),
              "src" -> JsString(scan.source),
              "seq" -> JsNumber(scan.sequence + 1),
              "ts" -> JsString(Instant.now().toString),
              "corr" -> JsNull,
              "data" -> JsObject(
                "operationId" -> JsString(operation),
                "batchId" -> JsString(scan.batchId),
                "rawDelta" -> JsNumber(raw),
                "deviceSource" -> JsString(source)
              )
            ).compactPrint
          )
        }
      }
      .toEither
      .left
      .map(_ =>
        "Poids correle refuse : verifier ARM operation/capture, rawDelta natif et scan partage"
      )
  }
}
