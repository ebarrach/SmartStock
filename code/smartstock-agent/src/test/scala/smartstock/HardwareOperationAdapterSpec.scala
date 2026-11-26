package smartstock

import org.scalatest.wordspec.AnyWordSpec
import smartstock.events.HardwareOperationAdapter
import smartstock.inventory._
import java.util.UUID
import spray.json._

class HardwareOperationAdapterSpec extends AnyWordSpec {
  "L'adaptation du poids arme" should {
    "exiger les identites partagees, garder une identite de retry et distinguer les captures volontaires" in {
      val operation = UUID.randomUUID().toString
      val capture = UUID.randomUUID().toString
      val scan = SensorRecord(
        UUID.randomUUID().toString,
        operation,
        "capture-" + capture.replace("-", ""),
        "lot",
        "datamatrix.detected",
        JsString("code"),
        1,
        System.currentTimeMillis()
      )
      val state = Inventory(sensorInputs = Vector(scan))
      val message = JsObject(
        "sensor" -> JsString("weight"),
        "source" -> JsString("hx711"),
        "operationId" -> JsString(operation),
        "captureId" -> JsString(capture),
        "rawDelta" -> JsNumber(-100)
      )
      def result(s: Inventory = state, m: JsObject = message) =
        HardwareOperationAdapter.envelope(m.compactPrint, s)
      val first = result().toOption.get.get.parseJson.asJsObject
      val retry = result().toOption.get.get.parseJson.asJsObject
      assert(first.fields("id") == retry.fields("id"))
      assert(first.fields("data") == retry.fields("data"))
      assert(result(state.copy(sensorInputs = Vector.empty)).isLeft)
      assert(
        result(
          state.copy(sensorInputs = Vector(scan, scan.copy(id = UUID.randomUUID().toString)))
        ).isLeft
      )
      assert(
        result(state.copy(serverSettings = Some(ServerSettings(withoutSensors = true)))) == Right(
          None
        )
      )
      assert(result(m = JsObject(message.fields - "rawDelta")) == Right(None))
      assert(result(m = JsObject(message.fields - "captureId")).isLeft)
      val nextCapture = UUID.randomUUID().toString
      val nextOperation = UUID.randomUUID().toString
      val next = result(
        state.copy(sensorInputs =
          Vector(
            scan
              .copy(operationId = nextOperation, source = "capture-" + nextCapture.replace("-", ""))
          )
        ),
        JsObject(
          message.fields ++ Map(
            "operationId" -> JsString(nextOperation),
            "captureId" -> JsString(nextCapture)
          )
        )
      )
      assert(next.toOption.get.get.parseJson.asJsObject.fields("id") != first.fields("id"))
    }
  }
}
