
package smartstock

import akka.actor.typed.{ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import smartstock.actors.{
  SmartStockManager,
  BatchActor
}

import smartstock.model.MedicationBatch

import java.time.LocalDate

object Main extends App {

  sealed trait TestMessage

  final case class BatchReply(
    result: BatchActor.BatchResult
  ) extends TestMessage

  final case class StockReply(
    stock: Map[String, Int]
  ) extends TestMessage

  val system = ActorSystem(
    Behaviors.setup[TestMessage] { context =>

      val manager = context.spawn(
        SmartStockManager(),
        "smartstock-manager"
      )

      val batchAdapter =
        context.messageAdapter[BatchActor.BatchResult](
          BatchReply.apply
        )

      val stockAdapter =
        context.messageAdapter[Map[String, Int]](
          StockReply.apply
        )

      val today = LocalDate.now()

      val batches = Vector(
        MedicationBatch(
          "LOT-A",
          "MED001",
          6,
          today.plusDays(10)
        ),
        MedicationBatch(
          "LOT-B",
          "MED001",
          4,
          today.plusDays(200)
        )
      )

      def registerNext(index: Int): Behavior[TestMessage] = {

        if (index >= batches.size) {

          println("=== SCAN DU LOT-A ===")

          manager ! SmartStockManager.ScanBatch(
            "LOT-A",
            "MED001",
            10.0
          )

          manager ! SmartStockManager.ChangeBatchWeight(
            -20.0,
            batchAdapter
          )

          waitingForRemoval

        } else {

          manager ! SmartStockManager.AddBatch(
            batches(index),
            batchAdapter
          )

          waitingForRegistration(index)
        }
      }

      def waitingForRegistration(
        index: Int
      ): Behavior[TestMessage] =
        Behaviors.receiveMessage {

          case BatchReply(BatchActor.BatchAdded(batch)) =>
            println(s"Lot enregistré : ${batch.batchId}")
            registerNext(index + 1)

          case BatchReply(BatchActor.BatchRejected(reason)) =>
            println(s"Erreur : $reason")
            Behaviors.stopped

          case _ =>
            Behaviors.same
        }

      def waitingForRemoval: Behavior[TestMessage] =
        Behaviors.receiveMessage {

          case BatchReply(BatchActor.BatchUpdated(batch)) =>
            println(
              s"Retrait effectué : ${batch.batchId} = ${batch.quantity} unités"
            )

            manager ! SmartStockManager.GetStock(
              stockAdapter
            )

            waitingForStock

          case BatchReply(BatchActor.BatchRejected(reason)) =>
            println(s"Erreur : $reason")
            Behaviors.stopped

          case _ =>
            Behaviors.same
        }

      def waitingForStock: Behavior[TestMessage] =
        Behaviors.receiveMessage {

          case StockReply(stock) =>
            println("=== STOCK GLOBAL APRÈS RETRAIT ===")

            stock.toVector
              .sortBy(_._1)
              .foreach {
                case (id, quantity) =>
                  println(s"$id : $quantity unités")
              }

            Behaviors.stopped

          case _ =>
            Behaviors.same
        }

      registerNext(0)
    },
    "SmartStockSystem"
  )
}
