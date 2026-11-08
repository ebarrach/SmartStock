package smartstock.anemone

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters._
import spray.json._

/** Adapter to the existing Java/Scala-2.12 bridge. Never computes pred or fan. */
final class AnemoneClient(base: URI, timeout: Duration = Duration.ofSeconds(3))(implicit
    ec: ExecutionContext
) extends AutoCloseable {
  require(Set("127.0.0.1", "localhost")(base.getHost), "Anemone bridge must remain local")
  private val client = HttpClient.newBuilder().connectTimeout(timeout).build()
  private def request(
      path: String,
      token: Option[String] = None,
      commandId: Option[String] = None
  ): Future[JsObject] = {
    val builder = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout)
    token.foreach(value => builder.header("X-SmartStock-Token", value))
    commandId.foreach(value => builder.header("X-Command-Id", value))
    if (token.isDefined) builder.POST(HttpRequest.BodyPublishers.noBody()) else builder.GET()
    client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).asScala.map {
      response =>
        require(response.statusCode() == 200, s"Anemone HTTP ${response.statusCode()}")
        response.body().parseJson.asJsObject
    }
  }
  def state(demo: String): Future[JsObject] = {
    require(Set("stock", "temperature")(demo), "Unknown Anemone session")
    request(s"/api/$demo/state")
  }
  def diagnostics(): Future[JsObject] = request("/api/diagnostics")
  def command(
      demo: String,
      operation: String,
      id: String,
      temperature: Option[BigDecimal] = None
  ): Future[JsObject] = {
    require(
      Set(
        "initialize",
        "reset",
        "events/weight",
        "events/datamatrix",
        "step",
        "run",
        "apply",
        "seed"
      )(
        operation
      )
    )
    require(
      Set("apply", "seed")(operation) == temperature.isDefined,
      "Temperature argument only for apply"
    )
    val suffix = temperature.map(v => s"?value=${v.bigDecimal.toPlainString}").getOrElse("")
    for {
      snapshot <- state(demo)
      token = snapshot.fields("csrfToken").convertTo[String](DefaultJsonProtocol.StringJsonFormat)
      result <- request(s"/api/$demo/$operation$suffix", Some(token), Some(id))
    } yield result
  }
  def close(): Unit = client.close()
}
