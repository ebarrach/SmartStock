package smartstock

import org.scalatest.wordspec.AnyWordSpecLike
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import smartstock.events.HardwareMqtt
import smartstock.inventory._
import java.time.Clock
import java.util.UUID

class HardwareMqttSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  "Le raccordement materiel" should {
    "fermer une seule fois les ressources du transport sans demarrer de reseau" in {
      val store = new InventoryStore {
        def load(): Inventory = Inventory()
        def save(state: Inventory): Unit = ()
      }
      val inventory = spawn(InventoryActor(store))
      var closes = 0
      val bridge = new HardwareMqtt(
        "tcp://127.0.0.1:1",
        "smartstock/events",
        inventory,
        _ => (),
        () => closes += 1
      )(system)
      bridge.close()
      bridge.close()
      assert(closes == 1)
    }
    "conserver date, unite brute et stock sans conversion" in {
      val reading = HardwareMqtt
        .reading(
          """{"sensor":"weight","event":"exit","value":1000,"delta":-600,"unit":"raw","source":"arduino-mkr","timestamp":"2026-11-26T10:00:00Z"}"""
        )
        .toOption
        .get
      assert(reading.kind == "weight.raw" && reading.value == 1000)
      val state = Inventory(
        products = Vector(Product("p", "P", "dm", 10, 1)),
        batches = Vector(Batch("b", "p", "cabinet", 40, "2027-12-31"))
      )
      val saved = InventoryRules
        .execute(state, RecordReading(reading), UUID.randomUUID().toString, Clock.systemUTC())
        .toOption
        .get
      assert(saved.batches == state.batches && saved.movements.isEmpty)
    }
    "adapter porte et statut sans les confondre avec infrarouge" in {
      assert(
        HardwareMqtt
          .reading("""{"sensor":"door","value":"open"}""")
          .toOption
          .get
          .kind == "door.changed"
      )
      assert(
        HardwareMqtt
          .reading("""{"sensor":"sensor_status","name":"humidity","status":"disconnected"}""")
          .toOption
          .get
          .value == 1
      )
    }
    "refuser un identifiant invalide avant le callback MQTT" in {
      assert(HardwareMqtt.reading("""{"sensor":"temperature","value":20,"id":"broken"}""").isLeft)
    }
  }
}
