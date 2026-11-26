package smartstock.events

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import smartstock.inventory._
import org.eclipse.paho.client.mqttv3._
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID
import java.util.concurrent.{Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.Await
import scala.concurrent.duration._
import spray.json._

/** One optional subscription, one canonical writer. Legacy messages have no durable event ID. */
final class HardwareMqtt(
    uri: String,
    topic: String,
    inventory: ActorRef[InventoryActor.Command],
    submitEnvelope: String => Unit,
    closeEnvelope: () => Unit
)(implicit
    system: ActorSystem[_]
) extends AutoCloseable {
  private implicit val timeout: Timeout = Timeout(5.seconds)
  private val closed = new AtomicBoolean(false)
  private val started = new AtomicBoolean(false)
  private val connectedOnce = new AtomicBoolean(false)
  private val subscribed = new AtomicBoolean(false)
  private val subscribing = new AtomicBoolean(false)
  private val connectionState = new AtomicInteger(-1)
  private val retry = Executors.newSingleThreadScheduledExecutor()
  private val client = new MqttClient(uri, MqttClient.generateClientId(), new MemoryPersistence())
  private val options = new MqttConnectOptions()
  options.setAutomaticReconnect(true)
  options.setConnectionTimeout(3)
  sys.env.get("SMARTSTOCK_MQTT_USER").foreach(options.setUserName)
  sys.env.get("SMARTSTOCK_MQTT_PASSWORD").foreach(p => options.setPassword(p.toCharArray))
  private def recordConnection(value: Int): Unit = if (
    !closed.get() && connectionState.getAndSet(value) != value
  ) {
    val reading =
      Reading("connection.changed", value.toDouble, "mqtt-broker", Instant.now().toString)
    inventory
      .ask[Either[DomainError, Inventory]](
        InventoryActor.Execute(RecordReading(reading), UUID.randomUUID().toString, _)
      )
      .onComplete {
        case scala.util.Success(Right(_)) => ()
        case _ => system.log.warn("MQTT connection state could not be persisted")
      }(system.executionContext)
  }
  private def subscribe(): Unit = if (!subscribed.get() && subscribing.compareAndSet(false, true)) {
    try { client.subscribe(topic, 1); subscribed.set(true); recordConnection(1) }
    catch { case _: MqttException => system.log.warn("MQTT subscription failed; retry scheduled") }
    finally subscribing.set(false)
  }

  client.setCallback(new MqttCallbackExtended {
    def connectComplete(reconnect: Boolean, serverURI: String): Unit = {
      connectedOnce.set(true)
      subscribed.set(false)
      subscribe()
      system.log.info("MQTT subscribed; reconnect={}", Boolean.box(reconnect))
    }
    def connectionLost(cause: Throwable): Unit =
      if (!closed.get()) {
        subscribed.set(false)
        recordConnection(0)
        system.log.warn("MQTT disconnected; reconnect scheduled by Paho")
      }
    def deliveryComplete(token: IMqttDeliveryToken): Unit = ()
    def messageArrived(name: String, message: MqttMessage): Unit = {
      if (!closed.get()) {
        val payload = new String(message.getPayload, UTF_8)
        val root = scala.util.Try(payload.parseJson.asJsObject).toOption
        if (root.exists(_.fields.get("v").contains(JsNumber(1)))) submitEnvelope(payload)
        else
          HardwareMqtt.reading(payload) match {
            case Left(reason) => system.log.warn("MQTT message rejected: {}", reason)
            case Right(reading) =>
              val fields = payload.parseJson.asJsObject.fields
              val id = fields.get("id") match {
                case Some(JsString(value)) => UUID.fromString(value).toString
                case None =>
                  UUID.randomUUID().toString // Delivery identity only; NOT a hardware operation ID.
                case _ => throw new IllegalArgumentException("Invalid event id")
              }
              val rawDelta = HardwareMessage.parse(payload).toOption.collect {
                case HardwareMessage.RawWeight(_, _, delta) =>
                  reading.copy(kind = "weight.delta.raw", value = delta.toDouble)
              }
              var refused = false
              (Vector(reading) ++ rawDelta.toVector).zipWithIndex.foreach {
                case (observation, index) if !refused =>
                  val observationId =
                    if (index == 0) id
                    else UUID.nameUUIDFromBytes((id + ":raw-delta").getBytes(UTF_8)).toString
                  Await.result(
                    inventory.ask[Either[DomainError, Inventory]](
                      InventoryActor.Execute(RecordReading(observation), observationId, _)
                    ),
                    5.seconds
                  ) match {
                    case Left(error) =>
                      refused = true
                      system.log.warn("MQTT observation refused: {}", error.code)
                    case Right(_) => ()
                  }
                case _ => ()
              }
          }
      }
    }
  })
  def start(): Unit = if (started.compareAndSet(false, true)) {
    retry.scheduleWithFixedDelay(
      new Runnable {
        def run(): Unit = if (!closed.get()) {
          if (client.isConnected) subscribe()
          else if (!connectedOnce.get()) {
            try client.connect(options)
            catch {
              case _: MqttException =>
                recordConnection(0);
                system.log.warn("MQTT initial connection unavailable; retry in 5s")
            }
          }
        }
      },
      0,
      5,
      TimeUnit.SECONDS
    )
  }
  def close(): Unit = if (closed.compareAndSet(false, true)) {
    retry.shutdownNow()
    try { if (client.isConnected) client.disconnect() }
    finally {
      try client.close()
      finally closeEnvelope()
    }
  }
}

object HardwareMqtt {
  def reading(payload: String): Either[String, Reading] =
    HardwareMessage.parse(payload).flatMap { event =>
      scala.util
        .Try {
          val fields = payload.parseJson.asJsObject.fields
          fields.get("id").foreach {
            case JsString(value) =>
              UUID.fromString(value)
              require(
                fields.contains("timestamp"),
                "Identifiable legacy observations require a stable timestamp"
              )
            case _ => throw new IllegalArgumentException("Invalid event id")
          }
          val source =
            fields.get("source").collect { case JsString(s) => s }.getOrElse("mqtt-hardware")
          val ts = fields
            .get("timestamp")
            .collect { case JsString(s) => Instant.parse(s).toString }
            .getOrElse(Instant.now().toString)
          event match {
            case HardwareMessage.Measure(sensor, value) =>
              Reading(sensor + ".measured", value, source, ts)
            case HardwareMessage.RawWeight(_, value, _) =>
              Reading("weight.raw", value.toDouble, source, ts)
            case HardwareMessage.Door(state) =>
              Reading("door.changed", if (state == "open") 1 else 0, source, ts)
            case HardwareMessage.Status(name, status) =>
              require(Set("connected", "disconnected")(status), "Unknown sensor status")
              Reading("sensor.failed", if (status == "connected") 0 else 1, source + "-" + name, ts)
          }
        }
        .toEither
        .left
        .map(_ => "Invalid hardware metadata")
    }
}
