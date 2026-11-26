package smartstock.events

import spray.json._
import scala.util.Try

/** Wire contract used by the colleague's Arduino/Phidget publishers. A raw HX711 delta is
  * deliberately not represented as grams.
  */
object HardwareMessage {
  sealed trait Observation
  final case class Measure(sensor: String, value: Double) extends Observation
  final case class RawWeight(direction: String, value: Long, delta: Long) extends Observation
  final case class Door(state: String) extends Observation
  final case class Status(name: String, status: String) extends Observation

  def parse(payload: String): Either[String, Observation] = Try {
    val root = payload.parseJson.asJsObject
    val data = root.fields.get("data").collect { case o: JsObject => o }.getOrElse(root)
    def string(key: String): String = data.fields(key) match {
      case JsString(s) => s
      case _           => throw new IllegalArgumentException(s"$key doit etre une chaine")
    }
    def number(key: String): BigDecimal = data.fields(key) match {
      case JsNumber(n) => n
      case _           => throw new IllegalArgumentException(s"$key doit etre numerique")
    }
    string("sensor") match {
      case sensor @ ("temperature" | "humidity") =>
        val value = number("value").toDouble
        require(
          value.isFinite && (sensor != "humidity" || (value >= 0 && value <= 100)),
          "Mesure invalide"
        )
        Measure(sensor, value)
      case "weight" =>
        require(string("unit") == "raw", "Unite de poids non prise en charge")
        val direction = string("event")
        val value = number("value"); val delta = number("delta")
        require(value.isValidLong && delta.isValidLong, "Poids brut entier requis")
        require(
          (direction == "entry" && delta > 0) || (direction == "exit" && delta < 0),
          "Direction incoherente"
        )
        RawWeight(direction, value.toLong, delta.toLong)
      case "door" =>
        val state = data.fields.get("state").getOrElse(data.fields("value")) match {
          case JsString(s) => s
          case _           => throw new IllegalArgumentException("Etat de porte invalide")
        }
        require(Set("open", "closed")(state), "Etat de porte invalide")
        Door(state)
      case "sensor_status" => Status(string("name"), string("status"))
      case _               => throw new IllegalArgumentException("Type materiel non pris en charge")
    }
  }.toEither.left.map(e => Option(e.getMessage).getOrElse("Message materiel invalide"))
}
