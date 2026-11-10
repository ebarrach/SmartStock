package smartstock.api

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import scala.util.control.NonFatal
import spray.json._
import smartstock.inventory._
import smartstock.inventory.InventoryJson._
import smartstock.events.StockInputs

final class ApiServer(
    inventory: ActorRef[InventoryActor.Command],
    bind: String = "127.0.0.1",
    requestedPort: Int = 0,
    simulated: Boolean = false,
    clock: Clock = Clock.systemUTC(),
    stockInputs: Option[StockInputs] = None,
    environmentLimits: EnvironmentLimits = EnvironmentLimits()
)(implicit system: ActorSystem[_])
    extends AutoCloseable {
  private val executor = Executors.newFixedThreadPool(4)
  private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(executor)
  private implicit val timeout: Timeout = Timeout(5.seconds)
  private val inputs = stockInputs.getOrElse(new StockInputs(inventory)(system, ec))
  private val server = HttpServer.create(new InetSocketAddress(bind, requestedPort), 32)
  server.setExecutor(executor)
  def port: Int = server.getAddress.getPort
  def start(): Unit = server.start()
  def close(): Unit = { server.stop(0); executor.shutdownNow() }

  private def error(e: DomainError): (Int, JsValue) =
    e.status -> JsObject("code" -> JsString(e.code), "message" -> JsString(e.message))
  private def snapshot(): Future[Inventory] = inventory.ask[Inventory](InventoryActor.Get.apply)
  private def execute(operation: InventoryOperation, id: String): Future[(Int, JsValue)] =
    inventory.ask[Either[DomainError, Inventory]](InventoryActor.Execute(operation, id, _)).map {
      case Left(e)      => error(e)
      case Right(state) => 200 -> publicSnapshot(state)
    }
  private def publicSnapshot(state: Inventory): JsObject = JsObject(
    "products" -> state.products.toJson,
    "batches" -> state.batches.toJson,
    "movements" -> state.movements.toJson,
    "readings" -> state.readings.toJson,
    "alerts" -> InventoryRules.alerts(state, clock, environmentLimits).toJson,
    "simulated" -> JsBoolean(simulated)
  )

  private def body(x: HttpExchange): JsObject = {
    require(
      Option(x.getRequestHeaders.getFirst("Content-Type"))
        .exists(_.split(";")(0).trim == "application/json"),
      "Content-Type application/json requis"
    )
    val bytes = x.getRequestBody.readNBytes(65537)
    require(bytes.length <= 65536, "Corps trop volumineux")
    new String(bytes, UTF_8).parseJson.asJsObject
  }
  private def commandId(x: HttpExchange): String =
    Option(x.getRequestHeaders.getFirst("X-Command-Id"))
      .flatMap(value =>
        scala.util
          .Try(java.util.UUID.fromString(value))
          .toOption
          .filter(_.toString.equalsIgnoreCase(value))
          .map(_.toString)
      )
      .getOrElse("")

  private def route(x: HttpExchange): Future[(Int, JsValue)] = {
    val path = x.getRequestURI.getPath
    val query = Option(x.getRequestURI.getRawQuery)
    if (
      query.nonEmpty && !(path == "/api/v1/stock" && query
        .exists(q => q == "location=cabinet" || q == "location=bag"))
    )
      return Future.successful(error(DomainError("invalid_query", "Parametres inconnus")))
    (x.getRequestMethod, path) match {
      case ("GET", "/api/v1/health") =>
        Future.successful(
          200 -> JsObject("status" -> JsString("ok"), "simulated" -> JsBoolean(simulated))
        )
      case ("GET", "/api/v1/snapshot") => snapshot().map(s => 200 -> publicSnapshot(s))
      case ("GET", "/api/v1/products") => snapshot().map(s => 200 -> s.products.toJson)
      case ("GET", p) if p.startsWith("/api/v1/products/") =>
        snapshot().map(s =>
          s.products
            .find(_.id == p.stripPrefix("/api/v1/products/"))
            .map(product => 200 -> product.toJson)
            .getOrElse(error(DomainError("not_found", "Produit inconnu", 404)))
        )
      case ("GET", "/api/v1/stock") =>
        snapshot().map { s =>
          val location = query.map(_.stripPrefix("location="))
          200 -> s.batches.filter(b => location.forall(_ == b.location)).toJson
        }
      case ("GET", "/api/v1/movements") => snapshot().map(s => 200 -> s.movements.toJson)
      case ("GET", "/api/v1/readings")  => snapshot().map(s => 200 -> s.readings.toJson)
      case ("GET", "/api/v1/alerts") =>
        snapshot().map(s => 200 -> InventoryRules.alerts(s, clock, environmentLimits).toJson)
      case ("POST", "/api/v1/products") =>
        val json = body(x)
        require(
          json.fields.keySet == Set("id", "name", "dataMatrix", "unitWeightGrams", "minStock"),
          "Champs produit invalides"
        )
        execute(RegisterProduct(json.convertTo[Product]), commandId(x))
      case ("POST", "/api/v1/batches") =>
        val json = body(x)
        require(
          json.fields.keySet == Set("id", "productId", "location", "quantity", "expiresOn"),
          "Champs lot invalides"
        )
        execute(RegisterBatch(json.convertTo[Batch]), commandId(x))
      case ("POST", "/api/v1/movements") =>
        val fields = body(x).fields
        require(fields.keySet == Set("batchId", "delta"), "Champs de mouvement invalides")
        execute(
          AdjustStock(fields("batchId").convertTo[String], fields("delta").convertTo[Int]),
          commandId(x)
        )
      case ("POST", "/api/v1/events") =>
        val fields = body(x).fields
        require(
          fields.keySet == Set("v", "id", "type", "src", "seq", "ts", "corr", "data"),
          "Enveloppe v1 requise"
        )
        require(
          fields("v") == JsNumber(1) && fields("seq").convertTo[Long] >= 0,
          "Version ou sequence invalide"
        )
        val id = fields("id").convertTo[String]
        require(java.util.UUID.fromString(id).version() == 4, "UUID v4 requis")
        require(fields("corr") == JsNull, "Observation autonome attendue")
        val timestamp = fields("ts").convertTo[String]
        java.time.Instant.parse(timestamp)
        require(timestamp.endsWith("Z") || timestamp.endsWith("+00:00"), "UTC requis")
        val data = fields("data").asJsObject.fields
        if (Set("datamatrix.detected", "weight.changed")(fields("type").convertTo[String])) {
          val kind = fields("type").convertTo[String]
          val key = if (kind == "datamatrix.detected") "code" else "grams"
          require(data.keySet == Set("batchId", "operationId", key), "Stock event fields invalid")
          java.util.UUID.fromString(data("operationId").convertTo[String])
          if (kind == "datamatrix.detected") data(key).convertTo[String]
          else data(key).convertTo[Double]
          val input = SensorRecord(
            id,
            data("operationId").convertTo[String],
            fields("src").convertTo[String],
            data("batchId").convertTo[String],
            kind,
            data(key),
            fields("seq").convertTo[Long],
            System.currentTimeMillis()
          )
          inputs.submit(input).map {
            case Left(e)  => error(e)
            case Right(_) => 200 -> JsObject("corr" -> JsString(id), "status" -> JsString("ok"))
          }
        } else {
          require(data.keySet == Set("value"), "Une mesure value est requise")
          val reading = Reading(
            fields("type").convertTo[String],
            data("value").convertTo[Double],
            fields("src").convertTo[String],
            fields("ts").convertTo[String]
          )
          require(
            reading.observedAt.endsWith("Z") || reading.observedAt.endsWith("+00:00"),
            "UTC requis"
          )
          execute(RecordReading(reading, Some(fields("seq").convertTo[Long])), id).flatMap {
            case (200, _) =>
              Future.successful(200 -> JsObject("corr" -> JsString(id), "status" -> JsString("ok")))
            case other => Future.successful(other)
          }
        }
      case (method, p)
          if Set(
            "/api/v1/products",
            "/api/v1/batches",
            "/api/v1/movements",
            "/api/v1/events",
            "/api/v1/health",
            "/api/v1/snapshot",
            "/api/v1/stock",
            "/api/v1/readings",
            "/api/v1/alerts"
          )(p) =>
        Future.successful(error(DomainError("method_not_allowed", s"Methode $method refusee", 405)))
      case _ => Future.successful(error(DomainError("not_found", "Route inconnue", 404)))
    }
  }
  server.createContext(
    "/",
    x => {
      val response =
        try route(x)
        catch {
          case NonFatal(_) =>
            Future.successful(error(DomainError("invalid_input", "JSON ou champs invalides")))
        }
      def send(code: Int, value: JsValue): Unit = try {
        val bytes = value.compactPrint.getBytes(UTF_8)
        x.getResponseHeaders.set("Content-Type", "application/json; charset=utf-8")
        x.getResponseHeaders.set("Cache-Control", "no-store")
        x.sendResponseHeaders(code, bytes.length)
        x.getResponseBody.write(bytes)
      } finally x.close()
      response.onComplete {
        case Success((code, value)) => send(code, value)
        case Failure(_) =>
          val (code, value) =
            error(DomainError("service_unavailable", "Service indisponible", 503));
          send(code, value)
      }
    }
  )
}
