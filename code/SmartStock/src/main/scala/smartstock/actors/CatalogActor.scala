
package smartstock.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors

import smartstock.model.Medication
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object CatalogActor {

  sealed trait Command

  sealed trait CatalogResult

  final case class MedicationAdded(
    medication: Medication
  ) extends CatalogResult

  final case class MedicationRejected(
    reason: String
  ) extends CatalogResult

  final case class AddMedication(
    medication: Medication,
    replyTo: ActorRef[CatalogResult]
  ) extends Command

  final case class FindByDataMatrix(
    code: String,
    replyTo: ActorRef[Option[Medication]]
  ) extends Command

  final case class GetAllMedications(
    replyTo: ActorRef[Vector[Medication]]
  ) extends Command

  // Nouveau message : demander les dates de péremption
  final case class CheckExpirations(
    replyTo: ActorRef[Vector[ExpirationInfo]]
  ) extends Command

  final case class ExpirationInfo(
    medication: Medication,
    daysRemaining: Long,
    status: String
  )

  def apply(): Behavior[Command] = {

    def behavior(
      medications: Map[String, Medication]
    ): Behavior[Command] =

      Behaviors.receiveMessage {

        case AddMedication(medication, replyTo) =>

          if (medication.id.trim.isEmpty ||
              medication.name.trim.isEmpty ||
              medication.datamatrix.trim.isEmpty ||
              medication.unitWeightGrams <= 0 ||
              medication.unitWeightGrams.isNaN ||
              medication.unitWeightGrams.isInfinity) {

            replyTo ! MedicationRejected(
              "Informations du médicament invalides"
            )

            Behaviors.same

          } else if (medications.contains(medication.id)) {

            replyTo ! MedicationRejected(
              "Identifiant déjà enregistré"
            )

            Behaviors.same

          } else if (medications.values.exists(
            _.datamatrix == medication.datamatrix
          )) {

            replyTo ! MedicationRejected(
              "DataMatrix déjà enregistré"
            )

            Behaviors.same

          } else {

            val updated = medications.updated(
              medication.id,
              medication
            )

            replyTo ! MedicationAdded(medication)

            behavior(updated)
          }

        case FindByDataMatrix(code, replyTo) =>

          val result = medications.values.find(
            _.datamatrix == code
          )

          replyTo ! result
          Behaviors.same

        case GetAllMedications(replyTo) =>

          replyTo ! medications.values.toVector
          Behaviors.same

        case CheckExpirations(replyTo) =>

          val today = LocalDate.now()

          val results = medications.values.toVector.map { medication =>

            val days = ChronoUnit.DAYS.between(
              today,
              medication.expirationDate
            )

            val status =
              if (days < 0) "PÉRIMÉ"
              else if (days <= 30) "BIENTÔT PÉRIMÉ"
              else "VALIDE"

            ExpirationInfo(medication, days, status)
          }

          replyTo ! results
          Behaviors.same
      }

    behavior(Map.empty[String, Medication])
  }
}
