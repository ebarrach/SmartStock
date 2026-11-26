package smartstock.inventory

import java.nio.file.{Files, Path, Paths}
import spray.json._
import InventoryJson._

object StorageMigration {
  private def preserves(source: JsValue, destination: JsValue): Boolean =
    (source, destination) match {
      case (a: JsObject, b: JsObject) =>
        a.fields.forall { case (key, value) =>
          b.fields.get(key).exists(preserves(value, _)) ||
          (value == JsNull && Set(
            "tare",
            "calibration",
            "lotCode",
            "serial",
            "serverSettings",
            "unitWeightGrams"
          )(key))
        }
      case (JsArray(a), JsArray(b)) =>
        a.size == b.size && a.zip(b).forall { case (x, y) => preserves(x, y) }
      case (a, b) => a == b
    }
  def migrate(source: Path, target: Path, allowExamples: Boolean): Inventory =
    InventoryProcessLock.withLock(source) { lockedSource =>
      require(Files.isRegularFile(lockedSource), "Source absente : aucune initialisation implicite")
      InventoryProcessLock.withLock(target.resolve("store")) { _ =>
        val original = new FileInventoryStore(lockedSource, allowExamples).load()
        require(
          preserves(Files.readString(lockedSource).parseJson, original.toJson),
          "Champs source non reconnus : aucun parametre ignore silencieusement"
        )
        val destination = new JsonDirectoryStore(target, allowExamples)
        val exists = Files.exists(target.resolve("commit.json")) || Files.exists(
          target.resolve("transaction.json")
        )
        val current = destination.load()
        if (exists)
          require(current == original, "Destination differente : aucune ancienne source recopiee")
        else destination.save(original)
        val verified = new JsonDirectoryStore(target, allowExamples).load()
        require(verified == original, "Migration non equivalente")
        verified
      }
    }
  def main(args: Array[String]): Unit = {
    require(
      args.length == 2 || (args.length == 3 && args(2) == "--allow-examples"),
      "StorageMigration <source.json> <dossier-cible> [--allow-examples]"
    )
    val state = migrate(
      Paths.get(args(0)).toAbsolutePath.normalize(),
      Paths.get(args(1)).toAbsolutePath.normalize(),
      args.contains("--allow-examples")
    )
    println(
      s"Migration equivalente : ${state.products.size} produits, ${state.batches.size} lots, ${state.movements.size} mouvements, ${state.receipts.size} recus"
    )
  }
}

/** Lossless rollback/export, including movements written AFTER migration. Never overwrites a file.
  */
object StorageExport {
  def export(directory: Path, destination: Path, allowExamples: Boolean): Inventory = {
    require(
      !destination.toAbsolutePath.normalize().startsWith(directory.toAbsolutePath.normalize()),
      "Export hors du dossier actif requis"
    )
    require(!Files.exists(destination), "Destination d'export existante : aucun ecrasement")
    InventoryProcessLock.withLock(directory.resolve("store")) { _ =>
      require(
        Files.exists(directory.resolve("commit.json")) || Files.exists(
          directory.resolve("transaction.json")
        ),
        "Stockage inexistant"
      )
      val state = new JsonDirectoryStore(directory, allowExamples).load()
      val output = new FileInventoryStore(destination, allowExamples)
      output.save(state)
      require(output.load() == state, "Export non equivalent")
      state
    }
  }
  def main(args: Array[String]): Unit = {
    require(
      args.length == 2 || (args.length == 3 && args(2) == "--allow-examples"),
      "StorageExport <dossier-actif> <nouveau-fichier.json> [--allow-examples]"
    )
    val state = export(
      Paths.get(args(0)).toAbsolutePath.normalize(),
      Paths.get(args(1)).toAbsolutePath.normalize(),
      args.contains("--allow-examples")
    )
    println(
      s"Export equivalent : ${state.products.size} produits, ${state.movements.size} mouvements, ${state.receipts.size} recus"
    )
  }
}
