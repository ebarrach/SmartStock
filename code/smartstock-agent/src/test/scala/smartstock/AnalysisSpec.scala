package smartstock

import org.scalatest.wordspec.AnyWordSpec
import smartstock.inventory._
import smartstock.inventory.InventoryJson._
import smartstock.analytics.Analysis
import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import java.nio.file.Files
import spray.json._

class AnalysisSpec extends AnyWordSpec {
  val clock: Clock = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC)
  val product = Product("p", "Compresses", "DM", 10, 5)
  val catalog = Catalog(
    false,
    Vector(CatalogEntry(product, "consommable", "unite", 40, 7, 10, 30, 7, "Au sec"))
  )
  def id(): String = UUID.randomUUID().toString
  def fixture(units: Vector[Int], stock: Int = 1): Inventory = {
    var quantity = 100
    val movements = units.zipWithIndex.flatMap { case (u, i) =>
      val month = f"${i + 5}%02d"
      quantity -= u
      val used = Movement(
        id(),
        "b",
        -u,
        quantity,
        s"2026-$month-10T12:00:00Z",
        id(),
        "p",
        "consumption",
        "fixture",
        ""
      )
      quantity += u
      Vector(
        used,
        Movement(
          id(),
          "b",
          u,
          quantity,
          s"2026-$month-20T12:00:00Z",
          id(),
          "p",
          "replenishment",
          "fixture",
          ""
        )
      )
    } :+ Movement(
      id(),
      "b",
      stock - 100,
      stock,
      "2026-11-19T12:00:00Z",
      id(),
      "p",
      "correction",
      "fixture",
      "inventaire"
    )
    Inventory(
      products = Vector(product),
      batches = Vector(Batch("b", "p", "cabinet", stock, "2027-12-31")),
      movements = movements,
      baselines = Map("b" -> StockBaseline(100, "2026-05-01T00:00:00Z"))
    )
  }
  def report(state: Inventory): JsObject = Analysis.report(state, catalog, 12, clock)
  def p(state: Inventory): JsObject =
    report(state).fields("products").asInstanceOf[JsArray].elements.head.asJsObject
  def suggestion(state: Inventory): JsObject = p(state).fields("suggestion").asJsObject
  def month(state: Inventory, key: String): JsObject = p(state)
    .fields("months")
    .asInstanceOf[JsArray]
    .elements
    .map(_.asJsObject)
    .find(_.fields("month") == JsString(key))
    .get

  "Historique et analyses" should {
    "conserver les agregats lorsque la fenetre brute depasse mille mesures" in {
      var state = Inventory()
      val first =
        RecordReading(Reading("temperature.measured", 20, "sensor", "2026-05-01T08:00:00Z"))
      val firstId = id()
      state = InventoryRules.execute(state, first, firstId, clock).toOption.get
      assert(InventoryRules.execute(state, first, firstId, clock) == Right(state))
      for (_ <- 1 to 1001)
        state = InventoryRules
          .execute(
            state,
            RecordReading(Reading("temperature.measured", 22, "sensor", "2026-11-01T08:00:00Z")),
            id(),
            clock
          )
          .toOption
          .get
      assert(state.readings.size == 1000)
      assert(!state.readings.exists(_.observedAt.startsWith("2026-05")))
      val day = report(state)
        .fields("sensors")
        .asInstanceOf[JsArray]
        .elements
        .map(_.asJsObject)
        .find(d =>
          d.fields("day") == JsString("2026-05-01") && d.fields("kind") == JsString(
            "temperature.measured"
          )
        )
        .get
      assert(day.fields("count") == JsNumber(1) && day.fields("mean") == JsNumber(20))
      assert(state.toJson.convertTo[Inventory].dailyMeasures == state.dailyMeasures)
    }
    "limiter un mois exceptionnel et ne pas lire les consommations futures" in {
      val stable = suggestion(fixture(Vector.fill(6)(10)))
        .fields("dailyConsumption")
        .asInstanceOf[JsNumber]
        .value
      val spike = suggestion(fixture(Vector(10, 10, 10, 10, 10, 90)))
        .fields("dailyConsumption")
        .asInstanceOf[JsNumber]
        .value
      assert(spike < stable * BigDecimal(2.1))
      val cutoff = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)
      def predicted(values: Vector[Int]): JsValue = Analysis
        .report(fixture(values), catalog, 12, cutoff)
        .fields("products")
        .asInstanceOf[JsArray]
        .elements
        .head
        .asJsObject
        .fields("suggestion")
        .asJsObject
        .fields("dailyConsumption")
      assert(predicted(Vector(10, 10, 10, 10, 10, 90)) == predicted(Vector.fill(6)(10)))
    }
    "conserver les anciens stocks lors de la lecture du format sans metadonnees" in {
      val state = fixture(Vector(10, 10), 17)
      val legacy = state.toJson.asJsObject.fields -- Set("baselines", "dataset")
      val movements = state.movements.map(m =>
        JsObject(m.toJson.asJsObject.fields -- Set("productId", "kind", "source", "reason"))
      )
      val dir = Files.createTempDirectory("legacy-history");
      val file = dir.resolve("inventory.json")
      try {
        Files.writeString(
          file,
          JsObject(legacy.updated("movements", JsArray(movements))).compactPrint
        )
        val loaded = new FileInventoryStore(file).load()
        assert(loaded.batches.head.quantity == 17 && loaded.movements.size == state.movements.size)
        assert(loaded.movements.forall(_.kind == "legacy") && loaded.baselines.isEmpty)
      } finally { Files.deleteIfExists(file); Files.delete(dir) }
    }
    "exclure reapprovisionnement et corrections de la consommation" in {
      val state = fixture(Vector(10, 12, 14))
      assert(month(state, "2026-05").fields("consumption") == JsNumber(10))
      assert(month(state, "2026-05").fields("entries") == JsNumber(10))
      assert(month(state, "2026-05").fields("exits") == JsNumber(10))
      assert(suggestion(state).fields("method") == JsString("moving-average"))
    }
    "distinguer absence et zero, et ne pas inventer un stock sans origine" in {
      val state = fixture(Vector(10, 10))
      assert(month(state, "2026-08").fields("consumption") == JsNull)
      assert(month(state.copy(baselines = Map.empty), "2026-05").fields("closingStock") == JsNull)
      assert(suggestion(state.copy(baselines = Map.empty)).fields("dailyConsumption") == JsNull)
    }
    "proposer zero lorsque le stock suffit et arrondir au conditionnement sinon" in {
      assert(suggestion(fixture(Vector(10, 10, 10), 100)).fields("quantity") == JsNumber(0))
      val quantity =
        suggestion(fixture(Vector(10, 10, 10))).fields("quantity").asInstanceOf[JsNumber].value
      assert(quantity > 0 && quantity % 10 == 0)
    }
    "traiter une consommation croissante, irreguliere et insuffisante" in {
      val increasing = suggestion(fixture(Vector(5, 8, 12, 16, 20, 24)))
      val stable = suggestion(fixture(Vector.fill(6)(10)))
      assert(
        increasing.fields("dailyConsumption").asInstanceOf[JsNumber].value > stable
          .fields("dailyConsumption")
          .asInstanceOf[JsNumber]
          .value
      )
      assert(
        suggestion(fixture(Vector(2, 40, 3, 45, 4, 40))).fields("quantity").isInstanceOf[JsNumber]
      )
      assert(suggestion(fixture(Vector(10))).fields("method") == JsString("minimum-threshold"))
      assert(increasing.fields("backtestSamples").asInstanceOf[JsNumber].value > 0)
    }
    "signaler les ruptures et exclure le mois incomplet" in {
      val state = fixture(Vector(10, 10, 10))
      val empty = state.copy(movements =
        state.movements.updated(0, state.movements.head.copy(quantityAfter = 0))
      )
      assert(suggestion(empty).fields("method") == JsString("minimum-threshold"))
      assert(month(state, "2026-11").fields("complete") == JsBoolean(false))
    }
    "respecter UTC aux limites mensuelles et agreger uniquement les mesures presentes" in {
      val state = fixture(Vector.empty).copy(
        movements = Vector(
          Movement(
            id(),
            "b",
            -5,
            95,
            "2026-10-01T00:30:00+02:00",
            id(),
            "p",
            "consumption",
            "test",
            ""
          )
        ),
        readings = Vector(
          Reading("temperature.measured", 20, "sensor", "2026-10-12T08:00:00Z"),
          Reading("temperature.measured", 24, "sensor", "2026-10-12T16:00:00Z")
        )
      )
      assert(month(state, "2026-09").fields("consumption") == JsNumber(5))
      val days = report(state).fields("sensors").asInstanceOf[JsArray].elements.map(_.asJsObject)
      assert(
        days
          .find(d =>
            d.fields("day") == JsString("2026-10-12") && d.fields("kind") == JsString(
              "temperature.measured"
            )
          )
          .get
          .fields("mean") == JsNumber(22)
      )
      assert(days.find(d => d.fields("day") == JsString("2026-10-13")).get.fields("mean") == JsNull)
    }
    "relire les anciens mouvements sans les classer en consommation" in {
      val old =
        """{"id":"m","batchId":"b","delta":-1,"quantityAfter":9,"occurredAt":"2026-01-01T00:00:00Z","eventId":"e"}""".parseJson
          .convertTo[Movement]
      assert(old.kind == "legacy")
    }
    "persister et dedupliquer chaque type de mouvement et refuser un fichier demo en reel" in {
      val dir = Files.createTempDirectory("typed-history")
      val path = dir.resolve("inventory.json")
      try {
        var state = fixture(Vector(10, 10), 20)
        for (
          (kind, delta) <- Vector(
            "consumption" -> -1,
            "replenishment" -> 3,
            "correction" -> 1,
            "loss" -> -1,
            "expiry" -> -1
          )
        ) {
          val key = id(); val operation = AdjustStock("b", delta, None, kind, "test", "motif")
          val next = InventoryRules.execute(state, operation, key, clock).toOption.get
          assert(InventoryRules.execute(next, operation, key, clock) == Right(next)); state = next
        }
        val store = new FileInventoryStore(path); store.save(state)
        assert(store.load() == state)
        store.save(state.copy(dataset = "demo"))
        intercept[IllegalArgumentException](store.load())
      } finally { Files.deleteIfExists(path); Files.delete(dir) }
    }
  }
}
