package smartstock.inventory

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.util.UUID
import scala.util.control.NonFatal
import spray.json._

final case class CatalogEntry(
    product: Product,
    category: String,
    unit: String,
    targetQuantity: Int,
    leadDays: Int,
    packSize: Int,
    coverageDays: Int,
    safetyDays: Int,
    storageConditions: String
)
final case class Catalog(exampleData: Boolean, entries: Vector[CatalogEntry]) {

  /** Only definitions change. Batches, quantities, events and receipts are retained. */
  def reconcile(state: Inventory): Inventory = {
    val definitions = entries.map(_.product)
    val ids = definitions.map(_.id).toSet
    val products = state.products.filterNot(p => ids(p.id)) ++ definitions
    require(
      products.map(_.dataMatrix).distinct.size == products.size,
      "Catalogue : DataMatrix en conflit avec un produit deja enregistre"
    )
    state.copy(products = products)
  }
}
object Catalog {
  import InventoryJson._
  private implicit val entryFormat: RootJsonFormat[CatalogEntry] = jsonFormat9(CatalogEntry.apply)
  private implicit val catalogFormat: RootJsonFormat[Catalog] = jsonFormat2(Catalog.apply)
  def json(catalog: Catalog): JsValue = catalog.toJson

  def parse(text: String, allowExamples: Boolean): Catalog = {
    val json = text.parseJson.asJsObject
    require(
      json.fields.keySet == Set("exampleData", "entries"),
      "Catalogue : champs racine invalides"
    )
    json.fields("entries").asInstanceOf[JsArray].elements.foreach { value =>
      require(
        value.asJsObject.fields.keySet == Set(
          "product",
          "category",
          "unit",
          "targetQuantity",
          "leadDays",
          "packSize",
          "coverageDays",
          "safetyDays",
          "storageConditions"
        ),
        "Catalogue : champs produit invalides"
      )
    }
    val catalog = json.convertTo[Catalog]
    require(!catalog.exampleData || allowExamples, "Catalogue fictif interdit en mode reel")
    require(
      catalog.entries.map(_.product.id).distinct.size == catalog.entries.size,
      "Catalogue : identifiant produit duplique"
    )
    catalog.entries.foldLeft(Inventory()) { (state, entry) =>
      require(
        entry.category.trim.nonEmpty && entry.unit.trim.nonEmpty,
        "Catalogue : categorie/unite requises"
      )
      require(
        entry.targetQuantity >= entry.product.minStock && entry.leadDays >= 0 && entry.leadDays <= 3650 &&
          entry.packSize > 0 && entry.coverageDays > 0 && entry.coverageDays <= 3650 && entry.safetyDays >= 0 && entry.safetyDays <= 3650,
        "Catalogue : parametres de reapprovisionnement invalides"
      )
      InventoryRules
        .execute(
          state,
          RegisterProduct(entry.product),
          UUID.randomUUID().toString,
          Clock.systemUTC()
        )
        .fold(e => throw new IllegalArgumentException("Catalogue : " + e.message), identity)
    }
    catalog
  }

  def load(path: Path, allowExamples: Boolean): Catalog =
    try parse(Files.readString(path, UTF_8), allowExamples)
    catch {
      case NonFatal(e) =>
        throw new IllegalArgumentException(s"Catalogue invalide [$path] : ${e.getMessage}", e)
    }
}
