package smartstock.analytics

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import smartstock.inventory._
import java.time.{Clock, Instant, LocalDate, YearMonth, ZoneOffset}
import spray.json._

object Analysis {
  def number(value: Double): JsValue = if (value.isFinite)
    JsNumber(BigDecimal(value).setScale(3, BigDecimal.RoundingMode.HALF_UP))
  else JsNull
  def date(value: String): LocalDate = Instant.parse(value).atZone(ZoneOffset.UTC).toLocalDate
  private def sum(m: Vector[Movement], kind: String): Long =
    m.filter(_.kind == kind).map(x => math.abs(x.delta.toLong)).sum
  private def estimate(rates: Vector[Double]): (Double, Double) = {
    val median = if (rates.nonEmpty) rates.sorted.apply(rates.size / 2) else 0.0
    val capped = rates.map(r => if (median > 0) r.min(median * 2) else r)
    val recent = capped.takeRight(3)
    val mean = if (recent.nonEmpty) recent.sum / recent.size else 0.0
    val older = capped.dropRight(recent.size).takeRight(3)
    val trend =
      if (older.nonEmpty && older.sum > 0)
        ((mean / (older.sum / older.size)) - 1).max(-0.2).min(0.2)
      else 0.0
    val daily = mean * (1 + trend)
    val deviation =
      if (recent.nonEmpty) math.sqrt(recent.map(r => math.pow(r - mean, 2)).sum / recent.size)
      else 0.0
    daily -> deviation
  }
  def report(state: Inventory, catalog: Catalog, months: Int, clock: Clock): JsObject = {
    require(Set(3, 6, 12)(months), "Periode attendue : 3, 6 ou 12 mois")
    val today = LocalDate.now(clock)
    val current = YearMonth.from(today)
    val periods = (0 until months).reverse.map(i => current.minusMonths(i.toLong)).toVector
    val from = periods.head.atDay(1)
    val products = state.products.map { product =>
      val batches = state.batches.filter(b => b.productId == product.id && b.location == "cabinet")
      val ids = batches.map(_.id).toSet
      val movements = state.movements.filter(m => ids(m.batchId)).sortBy(_.occurredAt)
      val recordedThrough =
        movements.map(m => date(m.occurredAt)).filter(!_.isAfter(today)).maxOption
      val known = batches.nonEmpty && batches.forall(b =>
        state.baselines
          .get(b.id)
          .exists(initial =>
            initial.quantity.toLong + movements
              .filter(_.batchId == b.id)
              .map(_.delta.toLong)
              .sum == b.quantity.toLong
          )
      )
      val observation =
        if (known) Some(batches.map(b => date(state.baselines(b.id).recordedAt)).min) else None
      val rows = periods.map { month =>
        val first = month.atDay(1); val end = month.atEndOfMonth
        val events = movements.filter(m => {
          val d = date(m.occurredAt); !d.isBefore(first) && !d.isAfter(end) && !d.isAfter(today)
        })
        val covered = events.nonEmpty && observation.exists(!_.isAfter(first)) && events.forall(
          _.kind != "legacy"
        )
        val complete = covered && end.isBefore(today) && recordedThrough.exists(!_.isBefore(end))
        val openingKnown =
          known && batches.forall(b => !date(state.baselines(b.id).recordedAt).isAfter(first))
        val stock =
          if (openingKnown) Some(batches.map { b =>
            state.baselines(b.id).quantity.toLong + movements
              .filter(m =>
                m.batchId == b.id && !date(m.occurredAt).isAfter(end) && !date(m.occurredAt)
                  .isAfter(today)
              )
              .map(_.delta.toLong)
              .sum
          }.sum)
          else None
        JsObject(
          "month" -> JsString(month.toString),
          "complete" -> JsBoolean(complete),
          "observed" -> JsBoolean(covered || events.nonEmpty),
          "consumption" -> (if (covered || events.exists(_.kind == "consumption"))
                              JsNumber(sum(events, "consumption"))
                            else JsNull),
          "entries" -> (if (covered || events.nonEmpty)
                          JsNumber(events.filter(_.delta > 0).map(_.delta.toLong).sum)
                        else JsNull),
          "exits" -> (if (covered || events.nonEmpty)
                        JsNumber(events.filter(_.delta < 0).map(m => -m.delta.toLong).sum)
                      else JsNull),
          "closingStock" -> stock.map(JsNumber(_)).getOrElse(JsNull),
          "days" -> JsNumber(month.lengthOfMonth()),
          "stockout" -> JsBoolean(events.exists(_.quantityAfter == 0))
        )
      }
      val usableRows = rows.filter(r =>
        r.fields("complete") == JsBoolean(true) && r.fields("stockout") == JsBoolean(false)
      )
      val rates = usableRows.map(r =>
        r.fields("consumption").asInstanceOf[JsNumber].value.toDouble / r
          .fields("days")
          .asInstanceOf[JsNumber]
          .value
          .toDouble
      )
      val enough =
        rates.size >= 2 && catalog.entries.exists(_.product.id == product.id) && !rows.exists(
          _.fields("stockout") == JsBoolean(true)
        )
      val (daily, deviation) = estimate(rates)
      val entry = catalog.entries.find(_.product.id == product.id)
      val lead = entry.map(_.leadDays).getOrElse(0)
      val horizon =
        lead + entry.map(_.coverageDays).getOrElse(30) + entry.map(_.safetyDays).getOrElse(7)
      val usable = batches
        .filter(b => !LocalDate.parse(b.expiresOn).isBefore(today.plusDays(lead.toLong)))
        .map(_.quantity.toLong)
        .sum
      val required =
        if (enough)
          math.max(
            product.minStock.toDouble,
            math.ceil(daily * horizon + deviation * math.sqrt(lead.toDouble))
          )
        else product.minStock.toDouble
      val needed =
        if (usable < required)
          math.max(required, entry.map(_.targetQuantity.toDouble).getOrElse(required)) - usable
        else 0.0
      val pack = entry.map(_.packSize).getOrElse(1)
      val quantity = math.ceil(needed / pack) * pack
      val comparisons = usableRows.indices.drop(2).map { i =>
        val training = usableRows.take(i)
        val historicalRates = training
          .map(r =>
            r.fields("consumption")
              .asInstanceOf[JsNumber]
              .value
              .toDouble / r.fields("days").asInstanceOf[JsNumber].value.toDouble
          )
          .toVector
        val rate = estimate(historicalRates)._1
        val actual = usableRows(i).fields("consumption").asInstanceOf[JsNumber].value.toDouble
        math.abs(rate * usableRows(i).fields("days").asInstanceOf[JsNumber].value.toDouble - actual)
      }
      JsObject(
        "productId" -> JsString(product.id),
        "name" -> JsString(product.name),
        "unit" -> JsString(entry.map(_.unit).getOrElse("unite")),
        "recordedThrough" -> recordedThrough.map(d => JsString(d.toString)).getOrElse(JsNull),
        "months" -> JsArray(rows),
        "suggestion" -> JsObject(
          "quantity" -> number(quantity),
          "stockUsed" -> JsNumber(usable),
          "dailyConsumption" -> (if (enough) number(daily) else JsNull),
          "coverageDays" -> (if (enough && daily > 0) number(usable / daily) else JsNull),
          "horizonDays" -> JsNumber(horizon),
          "leadDays" -> JsNumber(lead),
          "safetyDays" -> JsNumber(entry.map(_.safetyDays).getOrElse(7)),
          "packSize" -> JsNumber(pack),
          "targetQuantity" -> JsNumber(entry.map(_.targetQuantity).getOrElse(product.minStock)),
          "requiredStock" -> number(required),
          "dailyVariation" -> (if (enough) number(deviation) else JsNull),
          "observedDays" -> JsNumber(
            usableRows.map(_.fields("days").asInstanceOf[JsNumber].value.toInt).sum
          ),
          "observedConsumption" -> JsNumber(
            usableRows.map(_.fields("consumption").asInstanceOf[JsNumber].value).sum
          ),
          "method" -> JsString(if (enough) "moving-average" else "minimum-threshold"),
          "quality" -> JsString(if (enough) "observed-history" else "insufficient-history"),
          "explanation" -> JsString(
            if (enough)
              s"Moyenne mobile de ${rates.size.min(3)} mois complets (extremes plafonnes a deux fois la mediane), tendance bornee a 20%, horizon $horizon jours incluant delai et securite, variabilite et arrondi au lot de $pack. Stock excluant les lots perimant avant livraison."
            else
              "Historique trop court, incomplet, ancien non type, parametres catalogue absents ou rupture : repli explicite sur seuil minimal et quantite cible."
          ),
          "limits" -> JsString(
            "Observations enregistrees uniquement ; demande pendant ruptures inconnue ; aucune saisonnalite, commande fournisseur ni commande confirmee suivie. Pas de probabilite de confiance. Lots perimant pendant la couverture non modelises."
          ),
          "backtestMae" -> (if (comparisons.nonEmpty) number(comparisons.sum / comparisons.size)
                            else JsNull),
          "backtestSamples" -> JsNumber(comparisons.size)
        )
      )
    }
    val daily =
      if (state.dailyMeasures.nonEmpty) state.dailyMeasures else MeasureHistory.from(state.readings)
    val sensors = Vector("temperature.measured", "humidity.measured").flatMap { kind =>
      val grouped = daily.filter(_.kind == kind).groupBy(_.day)
      Iterator
        .iterate(from)(_.plusDays(1))
        .takeWhile(!_.isAfter(today))
        .map { day =>
          val observations = grouped.getOrElse(day.toString, Vector.empty)
          val count = observations.map(_.count).sum
          JsObject(
            "day" -> JsString(day.toString),
            "kind" -> JsString(kind),
            "count" -> JsNumber(count),
            "mean" -> (if (count > 0)
                         number((observations.map(_.total).sum / BigDecimal(count)).toDouble)
                       else JsNull),
            "min" -> (if (count > 0) number(observations.map(_.min).min) else JsNull),
            "max" -> (if (count > 0) number(observations.map(_.max).max) else JsNull)
          )
        }
        .toVector
    }
    JsObject(
      "calculatedAt" -> JsString(Instant.now(clock).toString),
      "from" -> JsString(from.toString),
      "to" -> JsString(today.toString),
      "products" -> JsArray(products),
      "sensors" -> JsArray(sensors),
      "exampleData" -> JsBoolean(catalog.exampleData)
    )
  }
}

object AnalysisActor {
  final case class Calculate(state: Inventory, months: Int, replyTo: ActorRef[JsObject])
  def apply(catalog: Catalog, clock: Clock): Behavior[Calculate] = Behaviors.receiveMessage {
    command =>
      command.replyTo ! Analysis.report(command.state, catalog, command.months, clock)
      Behaviors.same
  }
}
