package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import smartstock.ScannedBox
import smartstock.model.MedicationBatch
import java.time.LocalDate

/** Cree les Actors et delegue sans calculer ni memoriser les donnees metier. */
object SmartStockManager {
  sealed trait Command
  case class Scan(box: ScannedBox) extends Command
  case class WeightMovement(direction: String, value: Long, delta: Long) extends Command
  case class UpdateDoor(state: String) extends Command
  case class UpdateSensorStatus(name: String, status: String, message: String) extends Command

  case class MeasureTemperature(value: Double, replyTo: ActorRef[TemperatureActor.TemperatureResult]) extends Command
  case class SetTemperatureThresholds(min: Double, max: Double, replyTo: ActorRef[TemperatureActor.TemperatureResult]) extends Command
  case class MeasureHumidity(value: Double, replyTo: ActorRef[HumidityActor.HumidityResult]) extends Command
  case class GetTemperature(replyTo: ActorRef[Option[TemperatureActor.TemperatureResult]]) extends Command
  case class GetHumidity(replyTo: ActorRef[Option[HumidityActor.HumidityResult]]) extends Command
  case class GetDoor(replyTo: ActorRef[DoorActor.DoorStatus]) extends Command
  case class GetFailures(replyTo: ActorRef[Map[String, String]]) extends Command

  case class AddBatch(batch: MedicationBatch, replyTo: ActorRef[BatchActor.BatchResult]) extends Command
  case class AddToBatch(id: String, n: Int, replyTo: ActorRef[BatchActor.BatchResult]) extends Command
  case class RemoveFromBatch(id: String, n: Int, replyTo: ActorRef[BatchActor.BatchResult]) extends Command
  case class FindBatch(id: String, replyTo: ActorRef[Option[MedicationBatch]]) extends Command
  case class GetAllBatchStock(replyTo: ActorRef[Map[String, Int]]) extends Command
  case class GetAllBatches(replyTo: ActorRef[Vector[MedicationBatch]]) extends Command
  case class GetBatchExpirationAlerts(today: LocalDate, days: Int, replyTo: ActorRef[Vector[MedicationBatch]]) extends Command

  def apply(): Behavior[Command] = Behaviors.setup { ctx =>
    val batches = ctx.spawn(BatchActor(), "batches")
    val workflow = ctx.spawn(StockWorkflowActor(batches), "workflow")
    val weight = ctx.spawn(WeightActor(workflow), "weight")
    val door = ctx.spawn(DoorActor(), "door")
    val temperature = ctx.spawn(TemperatureActor(), "temperature")
    val humidity = ctx.spawn(HumidityActor(), "humidity")
    val failures = ctx.spawn(FailureActor(), "failures")

    Behaviors.receiveMessage {
      case Scan(b) => workflow ! StockWorkflowActor.Scan(b); Behaviors.same
      case WeightMovement(d, v, delta) => weight ! WeightActor.Movement(d, v, delta); Behaviors.same
      case UpdateDoor(s) => door ! DoorActor.State(s); Behaviors.same
      case UpdateSensorStatus(n, s, m) => failures ! FailureActor.SensorStatus(n, s, m); Behaviors.same
      case MeasureTemperature(v, r) => temperature ! TemperatureActor.MeasureTemperature(v, r); Behaviors.same
      case SetTemperatureThresholds(a, b, r) => temperature ! TemperatureActor.SetThresholds(a, b, r); Behaviors.same
      case MeasureHumidity(v, r) => humidity ! HumidityActor.MeasureHumidity(v, r); Behaviors.same
      case GetTemperature(r) => temperature ! TemperatureActor.GetStatus(r); Behaviors.same
      case GetHumidity(r) => humidity ! HumidityActor.GetStatus(r); Behaviors.same
      case GetDoor(r) => door ! DoorActor.GetStatus(r); Behaviors.same
      case GetFailures(r) => failures ! FailureActor.GetFailures(r); Behaviors.same
      case AddBatch(b, r) => batches ! BatchActor.AddBatch(b, r); Behaviors.same
      case AddToBatch(id, n, r) => batches ! BatchActor.AddToBatch(id, n, r); Behaviors.same
      case RemoveFromBatch(id, n, r) => batches ! BatchActor.RemoveFromBatch(id, n, r); Behaviors.same
      case FindBatch(id, r) => batches ! BatchActor.FindBatch(id, r); Behaviors.same
      case GetAllBatchStock(r) => batches ! BatchActor.GetAllStock(r); Behaviors.same
      case GetAllBatches(r) => batches ! BatchActor.GetAllBatches(r); Behaviors.same
      case GetBatchExpirationAlerts(today, days, r) => batches ! BatchActor.GetExpirationAlerts(today, days, r); Behaviors.same
    }
  }
}
