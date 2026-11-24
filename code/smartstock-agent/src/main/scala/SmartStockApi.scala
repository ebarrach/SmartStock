package smartstock

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import smartstock.actors._
import smartstock.model.MedicationBatch
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

object SmartStockApi {
  def start(system: ActorSystem[SmartStockMqttMain.Command]): HttpServer = {
    implicit val timeout: Timeout = Timeout(5.seconds)
    implicit val scheduler: akka.actor.typed.Scheduler = system.scheduler

    val manager = Await.result(
      system.ask[SmartStockMqttMain.ApiRefs](SmartStockMqttMain.GetApiRefs.apply), 5.seconds
    ).manager
    val port = sys.env.get("SMARTSTOCK_API_PORT").flatMap(_.toIntOption).getOrElse(8080)
    val token = sys.env.get("SMARTSTOCK_API_TOKEN").filter(_.nonEmpty)
    val origin = sys.env.getOrElse("SMARTSTOCK_CORS_ORIGIN", "http://localhost:5173")
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)

    def ask[A](f: akka.actor.typed.ActorRef[A] => SmartStockManager.Command): A =
      Await.result(manager.ask[A](f), 5.seconds)

    def batchJson(b: MedicationBatch): ujson.Value = ujson.Obj(
      "batchId" -> b.batchId, "gtin" -> b.medicationId,
      "quantity" -> b.quantity, "expiration" -> b.expirationDate.toString
    )

    def alerts(): Vector[ujson.Value] = {
      val temp = ask[Option[TemperatureActor.TemperatureResult]](SmartStockManager.GetTemperature.apply)
      val hum = ask[Option[HumidityActor.HumidityResult]](SmartStockManager.GetHumidity.apply)
      val door = ask[DoorActor.DoorStatus](SmartStockManager.GetDoor.apply)
      val failures = ask[Map[String, String]](SmartStockManager.GetFailures.apply)
      val t = temp.collect {
        case TemperatureActor.TemperatureAlert(v, msg) => ujson.Obj("sensor" -> "temperature", "value" -> v, "message" -> msg)
        case TemperatureActor.SensorFailure(msg) => ujson.Obj("sensor" -> "temperature", "message" -> msg)
      }.toVector
      val h = hum.collect {
        case HumidityActor.HumidityAlert(v, msg) => ujson.Obj("sensor" -> "humidity", "value" -> v, "message" -> msg)
        case HumidityActor.SensorFailure(msg) => ujson.Obj("sensor" -> "humidity", "message" -> msg)
      }.toVector
      val d = if (door.alert) Vector(ujson.Obj("sensor" -> "door", "message" -> "Porte ouverte depuis 60 secondes")) else Vector.empty
      val f = failures.toVector.map { case (name, msg) =>
        ujson.Obj("sensor" -> name, "message" -> msg)
      }
      t ++ h ++ d ++ f
    }

    def send(ex: HttpExchange, status: Int, body: ujson.Value): Unit = {
      val bytes = ujson.write(body).getBytes(StandardCharsets.UTF_8)
      ex.getResponseHeaders.set("Content-Type", "application/json; charset=utf-8")
      ex.getResponseHeaders.set("Access-Control-Allow-Origin", origin)
      ex.getResponseHeaders.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
      ex.getResponseHeaders.set("Access-Control-Allow-Headers", "Content-Type, Authorization")
      ex.sendResponseHeaders(status, bytes.length.toLong)
      val out = ex.getResponseBody
      try out.write(bytes) finally out.close()
    }

    server.createContext("/api", ex => {
      try {
        val path = ex.getRequestURI.getPath.stripSuffix("/")
        (ex.getRequestMethod, path) match {
          case ("OPTIONS", _) => send(ex, 200, ujson.Obj("ok" -> true))
          case ("GET", "/api/health") => send(ex, 200, ujson.Obj("status" -> "ok"))
          case ("GET", "/api/inventory") =>
            val stock = ask[Map[String, Int]](SmartStockManager.GetAllBatchStock.apply)
            send(ex, 200, ujson.Obj("stock" -> ujson.Obj.from(stock.map { case (k, v) => k -> ujson.Num(v) })))
          case ("GET", "/api/expirations") =>
            val days = Option(ex.getRequestURI.getRawQuery).getOrElse("").split("&")
              .find(_.startsWith("days=")).map(_.drop(5)).getOrElse("30").toIntOption
            days match {
              case Some(n) if n >= 0 && n <= 3650 =>
                val batches = ask[Vector[MedicationBatch]](r => SmartStockManager.GetBatchExpirationAlerts(LocalDate.now(), n, r))
                send(ex, 200, ujson.Obj("items" -> ujson.Arr.from(batches.map(batchJson))))
              case _ => send(ex, 400, ujson.Obj("error" -> "days invalide"))
            }
          case ("GET", p) if p == "/api/alerts" || p.startsWith("/api/alerts/") =>
            val sensor = if (p == "/api/alerts") None else Some(p.stripPrefix("/api/alerts/"))
            val items = alerts().filter(a => sensor.forall(_ == a("sensor").str))
            send(ex, 200, ujson.Obj("total" -> items.size, "items" -> ujson.Arr.from(items)))
          case ("POST", "/api/scans") =>
            if (!token.exists(t => ex.getRequestHeaders.getFirst("Authorization") == s"Bearer $t"))
              send(ex, 401, ujson.Obj("error" -> "Token requis"))
            else {
              val bytes = ex.getRequestBody.readNBytes(4097)
              if (bytes.length > 4096) send(ex, 413, ujson.Obj("error" -> "JSON trop volumineux"))
              else Try(ScannedBox.fromJson(ujson.read(new String(bytes, StandardCharsets.UTF_8)))) match {
                case scala.util.Success(box) =>
                  manager ! SmartStockManager.Scan(box)
                  send(ex, 202, ujson.Obj("accepted" -> true))
                case scala.util.Failure(_) => send(ex, 400, ujson.Obj("error" -> "Scan invalide"))
              }
            }
          case _ => send(ex, 404, ujson.Obj("error" -> "Route inconnue"))
        }
      } catch {
        case e: Exception =>
          system.log.error("API request failed", e)
          Try(send(ex, 500, ujson.Obj("error" -> "Erreur serveur")))
      } finally ex.close()
    })
    server.start()
    server
  }
}
