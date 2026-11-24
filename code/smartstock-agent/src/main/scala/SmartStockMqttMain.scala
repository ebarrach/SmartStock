package smartstock

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import org.eclipse.paho.client.mqttv3._
import smartstock.actors.{SmartStockManager, TemperatureActor, HumidityActor}
import java.nio.charset.StandardCharsets
import scala.util.Try

object SmartStockMqttMain {
  sealed trait Command
  case class MqttEvent(payload: String) extends Command
  case class GetApiRefs(replyTo: ActorRef[ApiRefs]) extends Command
  case class ApiRefs(manager: ActorRef[SmartStockManager.Command])

  def apply(): Behavior[Command] = Behaviors.setup { ctx =>
    val manager = ctx.spawn(SmartStockManager(), "manager")
    val tempReply = ctx.spawnAnonymous(Behaviors.ignore[TemperatureActor.TemperatureResult])
    val humReply = ctx.spawnAnonymous(Behaviors.ignore[HumidityActor.HumidityResult])
    Behaviors.receiveMessage {
      case GetApiRefs(reply) => reply ! ApiRefs(manager); Behaviors.same
      case MqttEvent(payload) =>
        MqttEventParser.parse(payload) match {
          case Right(MqttEventParser.Weight(d, v, delta)) => manager ! SmartStockManager.WeightMovement(d, v, delta)
          case Right(MqttEventParser.Door(s)) => manager ! SmartStockManager.UpdateDoor(s)
          case Right(MqttEventParser.Temperature(v)) => manager ! SmartStockManager.MeasureTemperature(v, tempReply)
          case Right(MqttEventParser.Humidity(v)) => manager ! SmartStockManager.MeasureHumidity(v, humReply)
          case Right(MqttEventParser.DataMatrix(box)) => manager ! SmartStockManager.Scan(box)
          case Right(MqttEventParser.SensorStatus(n, s, m)) => manager ! SmartStockManager.UpdateSensorStatus(n, s, m)
          case Left(reason) => ctx.log.warn("MQTT invalide: {}", reason)
          case _ => ()
        }
        Behaviors.same
    }
  }

  def main(args: Array[String]): Unit = {
    val host = sys.env.getOrElse("SMARTSTOCK_MQTT_HOST", "172.20.10.2")
    val port = sys.env.get("SMARTSTOCK_MQTT_PORT").flatMap(_.toIntOption).getOrElse(1883)
    val user = sys.env.getOrElse("SMARTSTOCK_MQTT_USER", "smartstock_mqtt")
    val password = sys.env("SMARTSTOCK_MQTT_PASSWORD")
    val system = ActorSystem(apply(), "SmartStock")
    val client = new MqttClient(s"tcp://$host:$port", MqttClient.generateClientId())
    val options = new MqttConnectOptions()
    options.setUserName(user)
    options.setPassword(password.toCharArray)
    options.setAutomaticReconnect(true)
    client.setCallback(new MqttCallbackExtended {
      override def connectComplete(reconnect: Boolean, uri: String): Unit =
        Try(client.subscribe("smartstock/events", 1)).failed.foreach(e => system.log.error("MQTT subscribe", e))
      override def connectionLost(cause: Throwable): Unit = system.log.error("MQTT disconnected", cause)
      override def messageArrived(topic: String, message: MqttMessage): Unit =
        system ! MqttEvent(new String(message.getPayload, StandardCharsets.UTF_8))
      override def deliveryComplete(token: IMqttDeliveryToken): Unit = ()
    })
    try {
      client.connect(options)
      val api = SmartStockApi.start(system)
      sys.addShutdownHook {
        api.stop(0)
        if (client.isConnected) client.disconnect()
        client.close()
        system.terminate()
      }
      scala.concurrent.Await.result(
  system.whenTerminated,
  scala.concurrent.duration.Duration.Inf
)
    } catch {
      case e: Exception =>
        client.close()
        system.terminate()
        throw e
    }
  }
}
