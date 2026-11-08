package smartstock

import org.scalatest.wordspec.AnyWordSpec
import smartstock.anemone.AnemoneClient
import java.net.URI
import java.util.UUID
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import spray.json._

class AnemoneIntegrationSpec extends AnyWordSpec {
  implicit val ec: ExecutionContext = ExecutionContext.global
  "Adaptateur Anemone canonique" should {
    "utiliser le vrai parser, reprendre un retrait et lire la puissance du moteur" in {
      val endpoint = sys.env.getOrElse(
        "SMARTSTOCK_ANEMONE_URL",
        cancel("Set SMARTSTOCK_ANEMONE_URL to run real-engine integration")
      )
      val client = new AnemoneClient(URI.create(endpoint))
      def command(demo: String, op: String, value: Option[BigDecimal] = None): JsObject =
        Await.result(client.command(demo, op, UUID.randomUUID().toString, value), 5.seconds)
      try {
        val diagnostics = Await.result(client.diagnostics(), 5.seconds)
        assert(
          diagnostics
            .fields("runtime")
            .asJsObject
            .fields("loadedClasses")
            .compactPrint
            .contains(".jar")
        )
        command("stock", "reset")
        command("stock", "events/weight")
        command("stock", "step")
        val blocked = command("stock", "step")
        assert(blocked.fields("stock") == JsNumber(40))
        assert(blocked.fields("executionStatus") == JsString("blocked"))
        command("stock", "events/datamatrix")
        val completed = (1 to 5).map(_ => command("stock", "step")).last
        assert(completed.fields("stock") == JsNumber(39))
        command("temperature", "reset")
        command("temperature", "apply", Some(BigDecimal(27)))
        def finish(): JsObject = {
          var state = command("temperature", "step")
          var count = 1
          while (state.fields("executionStatus") != JsString("completed") && count < 16) {
            state = command("temperature", "step"); count += 1
          }
          state
        }
        val warm = finish()
        assert(warm.fields("fanPower") == JsNumber(75))
        command("temperature", "apply", Some(BigDecimal(20)))
        val cool = finish()
        assert(cool.fields("fanPower") == JsNumber(0))
        command("temperature", "apply", Some(BigDecimal(23)))
        assert(finish().fields("fanPower") == JsNumber(25))
        command("temperature", "apply", Some(BigDecimal("21.9")))
        assert(finish().fields("fanPower") == JsNumber(25))
        command("temperature", "apply", Some(BigDecimal("21.4")))
        assert(finish().fields("fanPower") == JsNumber(0))
      } finally client.close()
    }
  }
}
