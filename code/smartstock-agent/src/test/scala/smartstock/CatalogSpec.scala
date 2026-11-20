package smartstock

import org.scalatest.wordspec.AnyWordSpec
import smartstock.inventory._

class CatalogSpec extends AnyWordSpec {
  private val definition =
    """{"exampleData":false,"entries":[{"product":{"id":"compresses","name":"Compresses","dataMatrix":"DM-C","unitWeightGrams":10,"minStock":5},"category":"consommable","unit":"unite","targetQuantity":50,"leadDays":7,"packSize":10,"coverageDays":30,"safetyDays":7,"storageConditions":"Au sec"}]}"""
  "Catalogue JSON" should {
    "charger les definitions sans reinitialiser les lots ni les historiques" in {
      val batch = Batch("lot", "compresses", "cabinet", 17, "2027-12-31")
      val existing = Inventory(
        products = Vector(Product("compresses", "Ancien nom", "DM-C", 10, 3)),
        batches = Vector(batch),
        movements = Vector(Movement("historic", "lot", -3, 17, "2026-11-01T12:00:00Z", "event")),
        receipts = Map("old" -> "receipt")
      )
      val result = Catalog.parse(definition, false).reconcile(existing)
      assert(result.batches == existing.batches)
      assert(result.receipts == existing.receipts)
      assert(result.movements == existing.movements)
      assert(result.products.head.name == "Compresses")
      assert(Catalog.parse(definition, false).reconcile(result) == result)
    }
    "refuser JSON invalide, parametres invalides et exemples en mode reel" in {
      intercept[Exception](Catalog.parse("{", false))
      intercept[Exception](
        Catalog.parse(definition.replace("\"packSize\":10", "\"packSize\":0"), false)
      )
      intercept[Exception](
        Catalog.parse(definition.replace("\"exampleData\":false", "\"exampleData\":true"), false)
      )
    }
  }
}
