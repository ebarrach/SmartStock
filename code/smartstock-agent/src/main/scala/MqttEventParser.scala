
package smartstock

import scala.util.Try

object MqttEventParser {

  sealed trait Event

  case class Weight(
    direction: String,
    value: Long,
    delta: Long
  ) extends Event

  case class Door(state: String) extends Event

  case class Temperature(value: Double) extends Event

  case class Humidity(value: Double) extends Event

  case class DataMatrix(box: ScannedBox) extends Event

  case class SensorStatus(
    name: String,
    status: String,
    message: String
  ) extends Event

  case class Unknown(name: String) extends Event

  def parse(payload: String): Either[String, Event] =
    Try {
      val json = ujson.read(payload)

      // Deux formats MQTT acceptes :
      //
      // 1. Format simple (Phidget)
      // {
      //   "sensor": "temperature",
      //   "value": 20.12
      // }
      //
      // 2. Format enveloppe (simulateur)
      // {
      //   "type": "sensor.reading",
      //   "data": {
      //     "sensor": "temperature",
      //     "value": 20.52
      //   }
      // }

      val data = json.objOpt
        .flatMap(_.get("data"))
        .filter(_.objOpt.isDefined)
        .getOrElse(json)

      // On cherche le capteur dans data ou
      // directement dans le message.
      val sensor = data.objOpt
        .flatMap(_.get("sensor"))
        .map(_.str)
        .getOrElse {
          json.objOpt
            .flatMap(_.get("sensor"))
            .map(_.str)
            .getOrElse("")
        }

      // Les messages sans capteur identifiable
      // sont traites comme des evenements inconnus.
      sensor match {

        case "weight" =>
          Weight(
            data("event").str,
            data("value").num.toLong,
            data("delta").num.toLong
          )

        case "door" =>
          Door(
            data.obj.get("state")
              .orElse(data.obj.get("value"))
              .map(_.str)
              .getOrElse("")
          )

        case "temperature" =>
          Temperature(data("value").num)

        case "humidity" =>
          Humidity(data("value").num)

        case "datamatrix" =>
          DataMatrix(
            ScannedBox.fromJson(data)
          )

        case "sensor_status" =>
          SensorStatus(
            data("name").str,
            data("status").str,
            data.obj.get("message")
              .map(_.str)
              .getOrElse("")
          )

        case other =>
          val eventType = json.objOpt
            .flatMap(_.get("type"))
            .map(_.str)
            .getOrElse("unknown")

          Unknown(
            if (other.nonEmpty) other else eventType
          )
      }

    }.toEither.left.map { error =>
      Option(error.getMessage)
        .getOrElse(error.toString)
    }
}
