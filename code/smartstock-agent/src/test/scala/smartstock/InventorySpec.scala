package smartstock

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import smartstock.inventory._
import java.nio.file.Files
import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID

class InventorySpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  val clock: Clock = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC)
  val product: Product = Product("compresses", "Compresses", "DM-1", 10, 5)
  val batch: Batch = Batch("LOT-C", product.id, "cabinet", 40, "2026-11-05")
  def id(): String = UUID.randomUUID().toString
  def run(state: Inventory, op: InventoryOperation): Inventory =
    InventoryRules.execute(state, op, id(), clock).toOption.get
  def seeded: Inventory = run(run(Inventory(), RegisterProduct(product)), RegisterBatch(batch))

  "InventoryRules" should {
    "ne pas masquer un sac insuffisant par le stock disponible en armoire" in {
      val state = seeded.copy(batches =
        seeded.batches :+ batch.copy(id = "LOT-BAG", location = "bag", quantity = 1)
      )
      val alerts = InventoryRules.alerts(state, clock)
      assert(alerts.exists(a => a.code == "low_stock" && a.subjectId == "compresses:bag"))
      assert(!alerts.exists(a => a.code == "low_stock" && a.subjectId == "compresses:cabinet"))
    }
    "alerter sur les dernieres mesures et supprimer une alerte apres retablissement" in {
      val state = seeded.copy(readings =
        Vector(
          Reading("temperature.measured", 28, "cabinet", "2026-10-29T00:00:00Z"),
          Reading("humidity.measured", 90, "cabinet", "2026-10-29T00:00:00Z"),
          Reading("sensor.failed", 1, "scale", "2026-10-29T00:00:00Z")
        )
      )
      assert(
        Set("temperature_alert", "humidity_alert", "sensor_failure").subsetOf(
          InventoryRules.alerts(state, clock).map(_.code).toSet
        )
      )
      val recovered = state.copy(readings =
        state.readings :+ Reading("sensor.failed", 0, "scale", "2026-10-29T00:01:00Z")
      )
      assert(!InventoryRules.alerts(recovered, clock).exists(_.code == "sensor_failure"))
    }
    "conserver les invariants pour tous les retraits de 1 a 40" in {
      (1 to 40).foreach { quantity =>
        val state = run(seeded, AdjustStock(batch.id, -quantity))
        assert(state.batches.head.quantity == 40 - quantity)
        assert(state.movements.size == 1)
      }
      assert(InventoryRules.execute(seeded, AdjustStock(batch.id, -41), id(), clock).isLeft)
      assert(
        InventoryRules.execute(seeded, AdjustStock(batch.id, Int.MaxValue), id(), clock).isLeft
      )
      assert(InventoryRules.execute(seeded, AdjustStock(batch.id, 0), id(), clock).isLeft)
    }
    "dedupliquer une commande et refuser une identite reutilisee" in {
      val key = id()
      val next = InventoryRules.execute(seeded, AdjustStock(batch.id, -1), key, clock).toOption.get
      assert(InventoryRules.execute(next, AdjustStock(batch.id, -1), key, clock) == Right(next))
      assert(
        InventoryRules
          .execute(next, AdjustStock(batch.id, 1), key, clock)
          .left
          .toOption
          .get
          .code == "id_conflict"
      )
      assert(next.movements.head.quantityAfter == 39)
    }
    "refuser references absentes, doublons et valeurs invalides" in {
      Vector(
        product.copy(id = ""),
        product.copy(unitWeightGrams = Double.NaN),
        product.copy(minStock = -1),
        product.copy(dataMatrix = " ")
      ).foreach { invalid =>
        assert(InventoryRules.execute(Inventory(), RegisterProduct(invalid), id(), clock).isLeft)
      }
      assert(
        InventoryRules
          .execute(seeded, RegisterProduct(product.copy(id = "other")), id(), clock)
          .isLeft
      )
      assert(
        InventoryRules
          .execute(
            seeded,
            RegisterBatch(batch.copy(id = "NEW", productId = "unknown")),
            id(),
            clock
          )
          .isLeft
      )
      assert(
        InventoryRules
          .execute(seeded, RegisterBatch(batch.copy(expiresOn = "2026-02-30")), id(), clock)
          .isLeft
      )
      assert(InventoryRules.execute(seeded, AdjustStock("unknown", 1), id(), clock).isLeft)
    }
    "calculer les alertes de peremption et de stock avec une horloge fixe" in {
      assert(InventoryRules.alerts(seeded, clock).exists(_.code == "expiring_soon"))
      val expired =
        seeded.copy(batches = Vector(batch.copy(expiresOn = "2026-10-28", quantity = 1)))
      assert(InventoryRules.alerts(expired, clock).map(_.code).toSet == Set("expired", "low_stock"))
      assert(
        !InventoryRules
          .alerts(expired.copy(batches = Vector(batch.copy(quantity = 0))), clock)
          .exists(_.code == "expired")
      )
    }
    "valider les mesures sans modifier le stock" in {
      val reading = Reading("humidity.measured", 50, "cabinet-01", Instant.now(clock).toString)
      assert(run(seeded, RecordReading(reading)).batches == seeded.batches)
      assert(
        InventoryRules.execute(seeded, RecordReading(reading.copy(value = 101)), id(), clock).isLeft
      )
    }
  }
  "InventoryActor" should {
    "refuser un fichier corrompu plutot que reinitialiser le stock" in {
      val directory = Files.createTempDirectory("smartstock-corrupt")
      val file = directory.resolve("inventory.json")
      try {
        Files.writeString(file, "{")
        assert(scala.util.Try(new FileInventoryStore(file).load()).isFailure)
        val store = new FileInventoryStore(file)
        store.save(seeded.copy(batches = Vector(batch.copy(quantity = -1))))
        assert(scala.util.Try(store.load()).isFailure)
      } finally { Files.delete(file); Files.delete(directory) }
    }
    "conserver stock, historique et deduplication apres redemarrage" in {
      val directory = Files.createTempDirectory("smartstock-store")
      val file = directory.resolve("inventory.json")
      val store = new FileInventoryStore(file)
      store.save(seeded)
      val actor = spawn(InventoryActor(store, clock))
      val replies = createTestProbe[Either[DomainError, Inventory]]()
      val key = id()
      actor ! InventoryActor.Execute(AdjustStock(batch.id, -1), key, replies.ref)
      assert(replies.receiveMessage().toOption.get.batches.head.quantity == 39)
      testKit.stop(actor)
      val restarted = spawn(InventoryActor(new FileInventoryStore(file), clock))
      restarted ! InventoryActor.Execute(AdjustStock(batch.id, -1), key, replies.ref)
      val state = replies.receiveMessage().toOption.get
      assert(state.batches.head.quantity == 39 && state.movements.size == 1)
      testKit.stop(restarted)
      Files.delete(file); Files.delete(directory)
    }
    "ne pas publier un stock modifie si le stockage echoue" in {
      val store = new InventoryStore {
        def load(): Inventory = seeded
        def save(state: Inventory): Unit = throw new java.io.IOException("disk full")
      }
      val actor = spawn(InventoryActor(store, clock))
      val replies = createTestProbe[Either[DomainError, Inventory]]()
      val snapshots = createTestProbe[Inventory]()
      actor ! InventoryActor.Execute(AdjustStock(batch.id, -1), id(), replies.ref)
      assert(replies.receiveMessage().left.toOption.get.status == 503)
      actor ! InventoryActor.Get(snapshots.ref)
      assert(snapshots.receiveMessage().batches.head.quantity == 40)
    }
  }
}
