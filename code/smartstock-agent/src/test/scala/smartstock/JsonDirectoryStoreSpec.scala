package smartstock

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import smartstock.inventory._
import smartstock.inventory.InventoryJson._
import java.nio.file.Files
import java.time.{Clock, Instant, ZoneOffset}
import java.util.UUID
import scala.util.control.ControlThrowable
import spray.json._

class JsonDirectoryStoreSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  private val clock = Clock.fixed(Instant.parse("2026-11-26T18:00:00Z"), ZoneOffset.UTC)
  private def id = UUID.randomUUID().toString
  private val removalId = id
  private val removal = AdjustStock("b", -1, None, "consumption", "manual", "reprise", Some("S1"))
  private val initial = Inventory(
    products = Vector(Product("p", "Nom utilisateur", "07613421008642", 10, 3)),
    batches = Vector(
      Batch(
        "b",
        "p",
        "cabinet",
        40,
        "2028-03-31",
        Some("L"),
        "historical-uninventoried",
        "Reprise explicite"
      )
    ),
    baselines = Map("b" -> StockBaseline(40, clock.instant().toString)),
    readings = Vector(Reading("weight.raw", 100, "hx711", clock.instant().toString)),
    sensorInputs =
      Vector(SensorRecord(id, id, "scale", "b", "weight.changed", JsNumber(-10), 4, 10)),
    serverSettings = Some(
      ServerSettings(
        tare = Some(TareCapture("hx711", 100, clock.instant().toString, id)),
        calibration = Some(ScaleCalibration("hx711", 100, 1100, 100, clock.instant().toString, id))
      )
    )
  )
  private val before = InventoryRules.execute(initial, removal, removalId, clock).toOption.get
  private val entryId = id
  private val entry = AdjustStock("b", 1, None, "replenishment", "manual", "retour", Some("S1"))
  private val nextState = InventoryRules.execute(before, entry, entryId, clock).toOption.get
  private class Crash extends ControlThrowable
  private def folder = Files.createTempDirectory("smartstock-json-transaction")

  "Le stockage JSON transactionnel" should {
    "refuser un champ source inconnu au lieu de perdre une donnee pendant la migration" in {
      val source = folder.resolve("old.json")
      val value =
        JsObject(before.toJson.asJsObject.fields + ("unknownParameter" -> JsString("preserve")))
      Files.writeString(source, value.prettyPrint)
      val target = folder
      intercept[IllegalArgumentException](StorageMigration.migrate(source, target, false))
      assert(!Files.exists(target.resolve("commit.json")))
    }
    "exporter le dernier etat pour retour arriere sans perdre les mouvements posterieurs a la migration" in {
      val dir = folder
      new JsonDirectoryStore(dir).save(nextState)
      val output = folder.resolve("rollback.json")
      assert(StorageExport.export(dir, output, false) == nextState)
      assert(new FileInventoryStore(output).load() == nextState)
      intercept[IllegalArgumentException](StorageExport.export(dir, output, false))
    }
    "relire tous les champs sans perdre series, parametres, calibration, recus ou mesures" in {
      val dir = folder
      val store = new JsonDirectoryStore(dir)
      store.save(before)
      assert(new JsonDirectoryStore(dir).load() == before)
      val serials =
        Files.readString(dir.resolve("units.json")).parseJson.asInstanceOf[JsArray].elements
      assert(serials.size == 1 && serials.head.asJsObject.fields("present") == JsBoolean(false))
      assert(serials.head.asJsObject.fields("initialPresenceUnknown") == JsBoolean(true))
      assert(InventoryRules.execute(store.load(), removal, removalId, clock) == Right(before))
    }
    "annuler une transaction interrompue apres chaque JSON et avant le commit" in {
      val stages =
        Vector("prepared", "before-commit") ++ new JsonDirectoryStore(folder).files.map("file:" + _)
      stages.foreach { point =>
        val dir = folder
        new JsonDirectoryStore(dir).save(before)
        val crashing = new JsonDirectoryStore(dir, stage = s => if (s == point) throw new Crash)
        intercept[Crash](crashing.save(nextState))
        assert(new JsonDirectoryStore(dir).load() == before, point)
        assert(!Files.exists(dir.resolve("transaction.json")))
      }
    }
    "reprendre une transaction commise et dedupliquer sa commande apres redemarrage" in {
      val dir = folder
      new JsonDirectoryStore(dir).save(before)
      val store = new JsonDirectoryStore(dir, stage = s => if (s == "committed") throw new Crash)
      intercept[Crash](store.save(nextState))
      val recovered = new JsonDirectoryStore(dir).load()
      assert(recovered == nextState)
      assert(InventoryRules.execute(recovered, entry, entryId, clock) == Right(nextState))
    }
    "annuler un echec d'ecriture et permettre une nouvelle tentative sans etat partiel" in {
      val dir = folder
      new JsonDirectoryStore(dir).save(before)
      var fail = true
      val store = new JsonDirectoryStore(
        dir,
        stage = s => if (fail && s == "file:batches.json") throw new java.io.IOException("test")
      )
      intercept[java.io.IOException](store.save(nextState))
      assert(store.load() == before)
      fail = false
      store.save(nextState)
      assert(store.load() == nextState)
    }
    "refuser de nouvelles ecritures si la restauration echoue puis recuperer au prochain demarrage" in {
      val dir = folder
      new JsonDirectoryStore(dir).save(before)
      val store = new JsonDirectoryStore(
        dir,
        stage =
          s => if (Set("file:batches.json", "recover")(s)) throw new java.io.IOException("test")
      )
      intercept[java.io.IOException](store.save(nextState))
      intercept[IllegalArgumentException](store.save(before))
      assert(new JsonDirectoryStore(dir).load() == before)
    }
    "ne pas publier une quantite ou un recu lorsque la persistance de l'acteur echoue" in {
      val dir = folder
      new JsonDirectoryStore(dir).save(before)
      val actor = spawn(
        InventoryActor(
          new JsonDirectoryStore(
            dir,
            stage = s => if (s == "file:movements.json") throw new java.io.IOException("test")
          ),
          clock
        )
      )
      val reply = createTestProbe[Either[DomainError, Inventory]]()
      actor ! InventoryActor.Execute(entry, entryId, reply.ref)
      assert(reply.receiveMessage().left.toOption.get.code == "storage_unavailable")
      val state = createTestProbe[Inventory]()
      actor ! InventoryActor.Get(state.ref)
      assert(state.receiveMessage() == before)
      assert(new JsonDirectoryStore(dir).load() == before)
    }
    "refuser des JSON melanges ou modifies sans transaction plutot que reinitialiser" in {
      val dir = folder
      new JsonDirectoryStore(dir).save(before)
      Files.writeString(dir.resolve("batches.json"), "[]")
      intercept[IllegalArgumentException](new JsonDirectoryStore(dir).load())
      val unmanaged = folder
      Files.writeString(unmanaged.resolve("batches.json"), "[]")
      intercept[IllegalArgumentException](new JsonDirectoryStore(unmanaged).load())
      assert(Files.readString(unmanaged.resolve("batches.json")) == "[]")
    }
    "migrer exactement une fois et refuser une ancienne source face a une destination avancee" in {
      val source = folder.resolve("old.json")
      new FileInventoryStore(source).save(before)
      val target = folder
      assert(StorageMigration.migrate(source, target, false) == before)
      assert(StorageMigration.migrate(source, target, false) == before)
      new JsonDirectoryStore(target).save(nextState)
      intercept[IllegalArgumentException](StorageMigration.migrate(source, target, false))
      assert(new JsonDirectoryStore(target).load() == nextState)
      assert(new FileInventoryStore(source).load() == before)
    }
    "annuler aussi une toute premiere initialisation interrompue et refuser un jeu Demo en reel" in {
      val dir = folder
      val store =
        new JsonDirectoryStore(dir, stage = s => if (s == "file:products.json") throw new Crash)
      intercept[Crash](store.save(before))
      assert(new JsonDirectoryStore(dir).load() == Inventory())
      new JsonDirectoryStore(dir, allowExamples = true).save(before.copy(dataset = "demo"))
      intercept[IllegalArgumentException](new JsonDirectoryStore(dir).load())
    }
  }
}
