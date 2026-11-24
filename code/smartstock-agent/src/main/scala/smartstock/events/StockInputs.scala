package smartstock.events

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import smartstock.inventory._
import java.util.UUID
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import spray.json._
import spray.json.DefaultJsonProtocol._

/** Durable sensor inbox -> pure correlation -> engine/domain confirmation -> ledger. */
final class StockInputs(
    inventory: ActorRef[InventoryActor.Command],
    windowMillis: Long = 5000,
    toleranceGrams: Double = 0.5
)(implicit
    system: ActorSystem[_],
    ec: ExecutionContext
) {
  private implicit val timeout: Timeout = Timeout(5.seconds)
  private var tail: Future[Unit] = Future.successful(())
  private val correlator = new Correlator(windowMillis, toleranceGrams)
  private def execute(op: InventoryOperation, id: String): Future[Either[DomainError, Inventory]] =
    inventory.ask[Either[DomainError, Inventory]](InventoryActor.Execute(op, id, _))
  private def businessId(operationId: String): String =
    UUID.nameUUIDFromBytes((operationId + ":confirmed").getBytes(UTF_8)).toString
  private def observation(input: SensorRecord): SensorInput = input.kind match {
    case "datamatrix.detected" =>
      ScanInput(
        UUID.fromString(input.id),
        UUID.fromString(input.operationId),
        input.source,
        input.batchId,
        input.value.convertTo[String]
      )
    case "weight.changed" =>
      WeightInput(
        UUID.fromString(input.id),
        UUID.fromString(input.operationId),
        input.source,
        input.batchId,
        input.value.convertTo[Double]
      )
  }
  def submit(input: SensorRecord): Future[Either[DomainError, Boolean]] = synchronized {
    val result = tail.recover { case _: Exception => () }.flatMap { _ =>
      inventory.ask[Inventory](InventoryActor.Get.apply).flatMap { initial =>
        val batch = initial.batches.find(_.id == input.batchId)
        val product = batch.flatMap(b => initial.products.find(_.id == b.productId))
        if (batch.isEmpty || product.isEmpty)
          Future.successful(Left(DomainError("unknown_batch", "Lot inconnu", 404)))
        else if (
          initial.receipts.contains(businessId(input.operationId)) && !initial.sensorInputs.exists(
            _.id == input.id
          )
        )
          Future.successful(Left(DomainError("operation_finished", "Operation deja terminee", 409)))
        else
          correlator
            .accept(
              CorrelationState(),
              observation(input),
              product.get,
              batch.get,
              input.receivedAt
            )
            ._2 match {
            case Left(error) =>
              Future.successful(Left(DomainError(error.code, "Observation invalide")))
            case Right(_) =>
              execute(RecordSensor(input), input.id).flatMap {
                case Left(error) => Future.successful(Left(error))
                case Right(state) if state.receipts.contains(businessId(input.operationId)) =>
                  Future.successful(Right(true))
                case Right(state) =>
                  val records = state.sensorInputs.filter(r =>
                    r.operationId == input.operationId &&
                      r.source == input.source && r.batchId == input.batchId
                  )
                  var correlation = CorrelationState()
                  var candidate: Either[CorrelationError, Option[StockCandidate]] = Right(None)
                  records.foreach { record =>
                    val step = correlator.accept(
                      correlation,
                      observation(record),
                      product.get,
                      batch.get,
                      record.receivedAt
                    )
                    correlation = step._1; candidate = step._2
                  }
                  candidate match {
                    case Left(error) =>
                      Future.successful(Left(DomainError(error.code, "Correlation refusee")))
                    case Right(None) => Future.successful(Right(false))
                    case Right(Some(value)) =>
                      val op = AdjustStock(value.batchId, value.delta, Some(batch.get.quantity))
                      InventoryRules.execute(
                        state,
                        op,
                        businessId(input.operationId),
                        java.time.Clock.systemUTC()
                      ) match {
                        case Left(error) => Future.successful(Left(error))
                        case Right(_) =>
                          execute(op, businessId(input.operationId)).map(_.map(_ => true))
                      }
                  }
              }
          }
      }
    }
    tail = result.map(_ => ())
    result
  }
}
