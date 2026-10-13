
package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import smartstock.model.{
  Medication,
  MedicationBatch,
  StockMovement
}

import java.time.LocalDate

object SmartStockManager {

  sealed trait Command
  sealed trait ScanResult

  final case class ScanSucceeded(
    medication: Medication
  ) extends ScanResult

  final case class ScanFailed(
    reason: String
  ) extends ScanResult

  final case class RegisterMedication(
    medication: Medication,
    replyTo: ActorRef[CatalogActor.CatalogResult]
  ) extends Command

  final case class ScanDataMatrix(
    code: String,
    replyTo: ActorRef[ScanResult]
  ) extends Command

  private final case class CatalogLookupResult(
    medication: Option[Medication],
    replyTo: ActorRef[ScanResult]
  ) extends Command

  final case class ScanProduct(
    medicationId: String,
    unitWeightGrams: Double
  ) extends Command

  final case class ChangeWeight(
    differenceGrams: Double,
    replyTo: ActorRef[StockActor.StockResult]
  ) extends Command

  // NOUVEAU : scan et mesure liés à un lot
  final case class ScanBatch(
    batchId: String,
    medicationId: String,
    unitWeightGrams: Double
  ) extends Command

  final case class ChangeBatchWeight(
    differenceGrams: Double,
    replyTo: ActorRef[BatchActor.BatchResult]
  ) extends Command

  final case class ManualAdd(
    medicationId: String,
    quantity: Int,
    replyTo: ActorRef[StockActor.StockResult]
  ) extends Command

  final case class ManualRemove(
    medicationId: String,
    quantity: Int,
    replyTo: ActorRef[StockActor.StockResult]
  ) extends Command

  final case class ReportFailure(
    component: String
  ) extends Command

  final case class ReportRecovery(
    component: String
  ) extends Command

  final case class GetStock(
    replyTo: ActorRef[Map[String, Int]]
  ) extends Command

  final case class GetHistory(
    replyTo: ActorRef[Vector[StockMovement]]
  ) extends Command

  final case class CheckExpirations(
    replyTo: ActorRef[Vector[CatalogActor.ExpirationInfo]]
  ) extends Command

  final case class StockExpirationAlert(
    info: CatalogActor.ExpirationInfo,
    quantity: Int
  )

  final case class GetStockExpirationAlerts(
    replyTo: ActorRef[Vector[StockExpirationAlert]]
  ) extends Command

  private final case class StockForAlerts(
    stock: Map[String, Int],
    replyTo: ActorRef[Vector[StockExpirationAlert]]
  ) extends Command

  private final case class ExpirationsForAlerts(
    infos: Vector[CatalogActor.ExpirationInfo],
    stock: Map[String, Int],
    replyTo: ActorRef[Vector[StockExpirationAlert]]
  ) extends Command

  final case class AddBatch(
    batch: MedicationBatch,
    replyTo: ActorRef[BatchActor.BatchResult]
  ) extends Command

  final case class AddToBatch(
    batchId: String,
    quantity: Int,
    replyTo: ActorRef[BatchActor.BatchResult]
  ) extends Command

  final case class RemoveFromBatch(
    batchId: String,
    quantity: Int,
    replyTo: ActorRef[BatchActor.BatchResult]
  ) extends Command

  final case class FindBatch(
    batchId: String,
    replyTo: ActorRef[Option[MedicationBatch]]
  ) extends Command

  final case class GetBatchesForMedication(
    medicationId: String,
    replyTo: ActorRef[Vector[MedicationBatch]]
  ) extends Command

  final case class GetTotalStock(
    medicationId: String,
    replyTo: ActorRef[Int]
  ) extends Command

  final case class GetAllBatchStock(
    replyTo: ActorRef[Map[String, Int]]
  ) extends Command

  final case class GetBatchExpirationAlerts(
    today: LocalDate,
    thresholdDays: Int,
    replyTo: ActorRef[Vector[MedicationBatch]]
  ) extends Command

  final case class GetBatchHistory(
    replyTo: ActorRef[Vector[BatchActor.BatchMovement]]
  ) extends Command

  final case class MeasureTemperature(
    value: Double,
    replyTo: ActorRef[TemperatureActor.TemperatureResult]
  ) extends Command

  final case class SimulateTemperatureFailure(
    replyTo: ActorRef[TemperatureActor.TemperatureResult]
  ) extends Command

  final case class RecoverTemperatureSensor(
    replyTo: ActorRef[TemperatureActor.TemperatureResult]
  ) extends Command

  def apply(): Behavior[Command] =
    Behaviors.setup { context =>

      val historyActor = context.spawn(
        HistoryActor(),
        "history-actor"
      )

      val stockActor = context.spawn(
        StockActor(historyActor),
        "stock-actor"
      )

      val failureActor = context.spawn(
        FailureActor(),
        "failure-actor"
      )

      val catalogActor = context.spawn(
        CatalogActor(),
        "catalog-actor"
      )

      val batchActor = context.spawn(
        BatchActor(),
        "batch-actor"
      )

      // BatchActor est maintenant transmis à HardwareActor.
      val hardwareActor = context.spawn(
        HardwareActor(
          stockActor,
          failureActor,
          batchActor
        ),
        "hardware-actor"
      )

      val temperatureActor = context.spawn(
        TemperatureActor(),
        "temperature-actor"
      )

      Behaviors.receiveMessage {

        case RegisterMedication(medication, replyTo) =>
          catalogActor ! CatalogActor.AddMedication(
            medication,
            replyTo
          )
          Behaviors.same

        case ScanDataMatrix(code, replyTo) =>
          val adapter = context.spawnAnonymous(
            Behaviors.receiveMessage[Option[Medication]] { result =>
              context.self ! CatalogLookupResult(result, replyTo)
              Behaviors.stopped
            }
          )

          catalogActor ! CatalogActor.FindByDataMatrix(
            code,
            adapter
          )
          Behaviors.same

        case CatalogLookupResult(Some(medication), replyTo) =>
          hardwareActor ! HardwareActor.ProductScanned(
            medication.id,
            medication.unitWeightGrams
          )

          replyTo ! ScanSucceeded(medication)
          Behaviors.same

        case CatalogLookupResult(None, replyTo) =>
          replyTo ! ScanFailed(
            "DataMatrix inconnu dans le catalogue"
          )
          Behaviors.same

        case ScanProduct(id, unitWeight) =>
          hardwareActor ! HardwareActor.ProductScanned(
            id,
            unitWeight
          )
          Behaviors.same

        case ChangeWeight(difference, replyTo) =>
          hardwareActor ! HardwareActor.WeightChanged(
            difference,
            replyTo
          )
          Behaviors.same

        case ScanBatch(batchId, medicationId, unitWeight) =>
          hardwareActor ! HardwareActor.BatchScanned(
            batchId,
            medicationId,
            unitWeight
          )
          Behaviors.same

        case ChangeBatchWeight(difference, replyTo) =>
          hardwareActor ! HardwareActor.BatchWeightChanged(
            difference,
            replyTo
          )
          Behaviors.same

        case ManualAdd(id, quantity, replyTo) =>
          stockActor ! StockActor.ManualAddStock(
            id,
            quantity,
            replyTo
          )
          Behaviors.same

        case ManualRemove(id, quantity, replyTo) =>
          stockActor ! StockActor.ManualRemoveStock(
            id,
            quantity,
            replyTo
          )
          Behaviors.same

        case ReportFailure(component) =>
          failureActor ! FailureActor.ComponentFailed(component)
          Behaviors.same

        case ReportRecovery(component) =>
          failureActor ! FailureActor.ComponentRecovered(component)
          Behaviors.same

        case GetStock(replyTo) =>
          batchActor ! BatchActor.GetAllStock(replyTo)
          Behaviors.same

        case GetHistory(replyTo) =>
          historyActor ! HistoryActor.GetHistory(replyTo)
          Behaviors.same

        case CheckExpirations(replyTo) =>
          catalogActor ! CatalogActor.CheckExpirations(replyTo)
          Behaviors.same

        case GetStockExpirationAlerts(replyTo) =>
          val adapter = context.spawnAnonymous(
            Behaviors.receiveMessage[Map[String, Int]] { stock =>
              context.self ! StockForAlerts(stock, replyTo)
              Behaviors.stopped
            }
          )

          batchActor ! BatchActor.GetAllStock(adapter)
          Behaviors.same

        case StockForAlerts(stock, replyTo) =>
          val adapter = context.spawnAnonymous(
            Behaviors.receiveMessage[
              Vector[CatalogActor.ExpirationInfo]
            ] { infos =>
              context.self ! ExpirationsForAlerts(
                infos,
                stock,
                replyTo
              )
              Behaviors.stopped
            }
          )

          catalogActor ! CatalogActor.CheckExpirations(adapter)
          Behaviors.same

        case ExpirationsForAlerts(infos, stock, replyTo) =>
          val alerts = infos
            .filter { info =>
              val quantity =
                stock.getOrElse(info.medication.id, 0)

              quantity > 0 && info.status != "VALIDE"
            }
            .map { info =>
              StockExpirationAlert(
                info,
                stock(info.medication.id)
              )
            }
            .sortBy(_.info.medication.id)

          replyTo ! alerts
          Behaviors.same

        case AddBatch(batch, replyTo) =>
          batchActor ! BatchActor.AddBatch(batch, replyTo)
          Behaviors.same

        case AddToBatch(batchId, quantity, replyTo) =>
          batchActor ! BatchActor.AddToBatch(
            batchId,
            quantity,
            replyTo
          )
          Behaviors.same

        case RemoveFromBatch(batchId, quantity, replyTo) =>
          batchActor ! BatchActor.RemoveFromBatch(
            batchId,
            quantity,
            replyTo
          )
          Behaviors.same

        case FindBatch(batchId, replyTo) =>
          batchActor ! BatchActor.FindBatch(batchId, replyTo)
          Behaviors.same

        case GetBatchesForMedication(medicationId, replyTo) =>
          batchActor ! BatchActor.GetBatchesForMedication(
            medicationId,
            replyTo
          )
          Behaviors.same

        case GetTotalStock(medicationId, replyTo) =>
          batchActor ! BatchActor.GetTotalStock(
            medicationId,
            replyTo
          )
          Behaviors.same

        case GetAllBatchStock(replyTo) =>
          batchActor ! BatchActor.GetAllStock(replyTo)
          Behaviors.same

        case GetBatchExpirationAlerts(today, thresholdDays, replyTo) =>
          batchActor ! BatchActor.GetExpirationAlerts(
            today,
            thresholdDays,
            replyTo
          )
          Behaviors.same

        case GetBatchHistory(replyTo) =>
          batchActor ! BatchActor.GetBatchHistory(replyTo)
          Behaviors.same

        case MeasureTemperature(value, replyTo) =>
          temperatureActor ! TemperatureActor.MeasureTemperature(
            value,
            replyTo
          )
          Behaviors.same

        case SimulateTemperatureFailure(replyTo) =>
          temperatureActor ! TemperatureActor.SimulateFailure(
            replyTo
          )
          Behaviors.same

        case RecoverTemperatureSensor(replyTo) =>
          temperatureActor ! TemperatureActor.RecoverSensor(
            replyTo
          )
          Behaviors.same
      }
    }
}
