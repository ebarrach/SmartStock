package smartstock

import java.nio.file.Files
import org.scalatest.wordspec.AnyWordSpec
import smartstock.inventory.InventoryProcessLock

class InventoryProcessLockSpec extends AnyWordSpec {
  "Le verrou de stockage" should {
    "refuser une seconde instance sur le meme chemin et liberer apres erreur" in {
      val directory = Files.createTempDirectory("smartstock-service-lock")
      val file = directory.resolve("inventory.json")
      try {
        InventoryProcessLock.withLock(file) { _ =>
          intercept[IllegalArgumentException] {
            InventoryProcessLock.withLock(directory.resolve("./inventory.json"))(_ =>
              fail("Second lock acquired")
            )
          }
          InventoryProcessLock.withLock(directory.resolve("demo.json"))(_ => ())
        }
        intercept[IllegalStateException] {
          InventoryProcessLock.withLock(file)(_ => throw new IllegalStateException("startup error"))
        }
        InventoryProcessLock.withLock(file)(_ => assert(!Files.exists(file)))
      } finally {
        Files.deleteIfExists(directory.resolve("inventory.json.lock"))
        Files.deleteIfExists(directory.resolve("demo.json.lock"))
        Files.delete(directory)
      }
    }
  }
}
