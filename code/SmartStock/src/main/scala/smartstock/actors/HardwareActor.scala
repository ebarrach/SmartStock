
package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

object HardwareActor {

  sealed trait Command

  final case class ProductScanned(
    medicationId: String,
    unitWeightGrams: Double
  ) extends Command

  // NOUVEAU : le scan précise le lot
  final case class BatchScanned(
    batchId: String,
    medicationId: String,
    unitWeightGrams: Double
  ) extends Command

  final case class WeightChanged(
    differenceGrams: Double,
    replyTo: ActorRef[StockActor.StockResult]
  ) extends Command

  // NOUVEAU : mesure liée à un lot
  final case class BatchWeightChanged(
    differenceGrams: Double,
    replyTo: ActorRef[BatchActor.BatchResult]
  ) extends Command

  private final case class StockResponse(
    result: StockActor.StockResult
  ) extends Command

  private final case class BatchResponse(
    result: BatchActor.BatchResult
  ) extends Command

  private final case class FailuresReceived(
    failedComponents: Set[String]
  ) extends Command

  private final case class BatchFailuresReceived(
    failedComponents: Set[String]
  ) extends Command

  def apply(
    stockActor: ActorRef[StockActor.Command],
    failureActor: ActorRef[FailureActor.Command],
    batchActor: ActorRef[BatchActor.Command]
  ): Behavior[Command] = {

    Behaviors.setup { context =>

      val stockAdapter =
        context.messageAdapter[StockActor.StockResult](
          StockResponse.apply
        )

      val batchAdapter =
        context.messageAdapter[BatchActor.BatchResult](
          BatchResponse.apply
        )

      val failureAdapter =
        context.messageAdapter[Set[String]](
          FailuresReceived.apply
        )

      // Un acteur temporaire évite de confondre
      // les réponses des deux types d'opérations.
      def behavior(
        lastScanned: Option[(String, Double)],
        lastBatchScanned: Option[(String, String, Double)],
        pendingStockReply: Option[ActorRef[StockActor.StockResult]],
        pendingBatchReply: Option[ActorRef[BatchActor.BatchResult]],
        pendingWeight: Option[Double],
        checkingFailures: Boolean
      ): Behavior[Command] = {

        Behaviors.receiveMessage {

          case ProductScanned(id, unitWeight) =>
            if (pendingStockReply.nonEmpty ||
                pendingBatchReply.nonEmpty ||
                checkingFailures) {
              println("Opération en cours")
              Behaviors.same
            } else if (unitWeight > 0 &&
                       !unitWeight.isNaN &&
                       !unitWeight.isInfinity) {
              println(s"DataMatrix scanné : $id")
              behavior(
                Some((id, unitWeight)),
                None,
                None,
                None,
                None,
                false
              )
            } else {
              println("Poids unitaire invalide")
              Behaviors.same
            }

          case BatchScanned(batchId, medicationId, unitWeight) =>
            if (pendingStockReply.nonEmpty ||
                pendingBatchReply.nonEmpty ||
                checkingFailures) {
              println("Opération en cours")
              Behaviors.same
            } else if (batchId.trim.isEmpty ||
                       medicationId.trim.isEmpty ||
                       unitWeight <= 0 ||
                       unitWeight.isNaN ||
                       unitWeight.isInfinity) {
              println("Informations du lot invalides")
              Behaviors.same
            } else {
              println(s"Lot scanné : $batchId")
              behavior(
                None,
                Some((batchId, medicationId, unitWeight)),
                None,
                None,
                None,
                false
              )
            }

          case WeightChanged(difference, replyTo) =>
            if (pendingStockReply.nonEmpty ||
                pendingBatchReply.nonEmpty ||
                checkingFailures) {
              replyTo ! StockActor.StockRejected(
                "UNKNOWN",
                "Opération en cours"
              )
              Behaviors.same
            } else {
              failureActor ! FailureActor.GetFailures(
                failureAdapter
              )

              behavior(
                lastScanned,
                lastBatchScanned,
                Some(replyTo),
                None,
                Some(difference),
                true
              )
            }

          case BatchWeightChanged(difference, replyTo) =>
            if (pendingStockReply.nonEmpty ||
                pendingBatchReply.nonEmpty ||
                checkingFailures) {
              replyTo ! BatchActor.BatchRejected(
                "Opération en cours"
              )
              Behaviors.same
            } else if (difference.isNaN ||
                       difference.isInfinity) {
              replyTo ! BatchActor.BatchRejected(
                "Variation de poids invalide"
              )
              Behaviors.same
            } else {
              val responseAdapter =
                context.spawnAnonymous(
                  Behaviors.receiveMessage[Set[String]] { failures =>
                    context.self ! BatchFailuresReceived(failures)
                    Behaviors.stopped
                  }
                )

              failureActor ! FailureActor.GetFailures(
                responseAdapter
              )

              behavior(
                lastScanned,
                lastBatchScanned,
                None,
                Some(replyTo),
                Some(difference),
                true
              )
            }

          case FailuresReceived(failedComponents) =>
            if (!checkingFailures || pendingStockReply.isEmpty) {
              Behaviors.same
            } else {

              val replyTo = pendingStockReply.get
              val difference = pendingWeight.get

              if (failedComponents.contains("DataMatrix") ||
                  failedComponents.contains("WeightSensor")) {

                replyTo ! StockActor.StockRejected(
                  "UNKNOWN",
                  "Capteur indispensable en panne"
                )

                behavior(None, None, None, None, None, false)

              } else {

                lastScanned match {

                  case Some((id, unitWeight)) =>
                    val units = math.round(
                      math.abs(difference) / unitWeight
                    ).toInt

                    if (units <= 0) {
                      replyTo ! StockActor.StockRejected(
                        id,
                        "Variation de poids insuffisante"
                      )

                      behavior(
                        None, None, None, None, None, false
                      )

                    } else {

                      if (difference > 0) {
                        stockActor ! StockActor.AddStock(
                          id,
                          units,
                          stockAdapter
                        )
                      } else {
                        stockActor ! StockActor.RemoveStock(
                          id,
                          units,
                          stockAdapter
                        )
                      }

                      behavior(
                        None,
                        None,
                        pendingStockReply,
                        None,
                        None,
                        false
                      )
                    }

                  case None =>
                    replyTo ! StockActor.StockRejected(
                      "UNKNOWN",
                      "Aucun médicament scanné"
                    )

                    behavior(
                      None, None, None, None, None, false
                    )
                }
              }
            }

          case BatchFailuresReceived(failedComponents) =>
            if (!checkingFailures || pendingBatchReply.isEmpty) {
              Behaviors.same
            } else {

              val replyTo = pendingBatchReply.get
              val difference = pendingWeight.get

              if (failedComponents.contains("DataMatrix") ||
                  failedComponents.contains("WeightSensor")) {

                replyTo ! BatchActor.BatchRejected(
                  "Capteur indispensable en panne"
                )

                behavior(None, None, None, None, None, false)

              } else {

                lastBatchScanned match {

                  case Some((batchId, medicationId, unitWeight)) =>

                    val unitsDouble =
                      math.abs(difference) / unitWeight

                    if (unitsDouble > Int.MaxValue) {
                      replyTo ! BatchActor.BatchRejected(
                        "Variation de poids trop importante"
                      )

                      behavior(
                        None, None, None, None, None, false
                      )

                    } else {

                      val units = math.round(unitsDouble).toInt

                      if (units <= 0) {
                        replyTo ! BatchActor.BatchRejected(
                          "Variation de poids insuffisante"
                        )

                        behavior(
                          None, None, None, None, None, false
                        )

                      } else {

                        // La modification passe par BatchActor.
                        if (difference > 0) {
                          batchActor ! BatchActor.AddToBatch(
                            batchId,
                            units,
                            batchAdapter
                          )
                        } else {
                          batchActor ! BatchActor.RemoveFromBatch(
                            batchId,
                            units,
                            batchAdapter
                          )
                        }

                        behavior(
                          None,
                          None,
                          None,
                          pendingBatchReply,
                          None,
                          false
                        )
                      }
                    }

                  case None =>
                    replyTo ! BatchActor.BatchRejected(
                      "Aucun lot scanné"
                    )

                    behavior(
                      None, None, None, None, None, false
                    )
                }
              }
            }

          case StockResponse(result) =>
            pendingStockReply.foreach(_ ! result)

            behavior(
              lastScanned,
              lastBatchScanned,
              None,
              None,
              None,
              false
            )

          case BatchResponse(result) =>
            pendingBatchReply.foreach(_ ! result)

            behavior(
              lastScanned,
              lastBatchScanned,
              None,
              None,
              None,
              false
            )
        }
      }

      behavior(
        None,
        None,
        None,
        None,
        None,
        false
      )
    }
  }
}
