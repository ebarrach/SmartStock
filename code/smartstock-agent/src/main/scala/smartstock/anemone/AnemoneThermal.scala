package smartstock.anemone

import smartstock.inventory.Reading
import scala.concurrent.{ExecutionContext, Future}
import spray.json._

/** Serializes the existing single thermal session; fan power comes only from Anemone. */
final class AnemoneThermal(client: AnemoneClient)(implicit ec: ExecutionContext) {
  private val cache =
    scala.collection.mutable.LinkedHashMap.empty[String, (Reading, Future[Reading])]
  private var tail: Future[Unit] = Future.successful(())
  def evaluate(reading: Reading, eventId: String): Future[Reading] = synchronized {
    cache.get(eventId) match {
      case Some((previous, future)) if previous == reading => return future
      case Some(_) =>
        return Future.failed(new IllegalArgumentException("Conflicting thermal event identity"))
      case None => ()
    }
    val evaluated = tail.recover { case _: Exception => () }.flatMap { _ =>
      def finish(step: Int): Future[JsObject] = {
        require(step <= 16, "Anemone step limit reached")
        client.command("temperature", "step", eventId + s":step:$step").flatMap { state =>
          state.fields("executionStatus") match {
            case JsString("completed") => Future.successful(state)
            case JsString("paused")    => finish(step + 1)
            case _ =>
              Future.failed(new IllegalStateException("Anemone thermal decision incomplete"))
          }
        }
      }
      for {
        _ <- client.command(
          "temperature",
          "apply",
          eventId + ":apply",
          Some(BigDecimal(reading.value))
        )
        result <- finish(1)
      } yield {
        val power = result.fields("fanPower") match {
          case JsNumber(value) if value >= 0 && value <= 100 => value.toDouble
          case _ => throw new IllegalStateException("Anemone has not produced a fan decision")
        }
        Reading("fan.changed", power, "anemone", reading.observedAt)
      }
    }
    cache.update(eventId, reading -> evaluated)
    if (cache.size > 1024) cache.remove(cache.head._1)
    evaluated.failed.foreach { _ => synchronized { cache.remove(eventId); () } }
    tail = evaluated.map(_ => ())
    evaluated
  }
}
