package smartstock

import org.scalatest.wordspec.AnyWordSpec
import smartstock.events._
import smartstock.inventory._
import java.util.UUID

class CorrelatorSpec extends AnyWordSpec {
  val product: Product = Product("compresses", "Compresses", "DM-C", 10, 5)
  val batch: Batch = Batch("LOT-C", product.id, "cabinet", 40, "2027-06-30")
  def scan(op: UUID): ScanInput = ScanInput(UUID.randomUUID(), op, "cabinet-01", batch.id, "DM-C")
  def weight(op: UUID, grams: Double = -10): WeightInput =
    WeightInput(UUID.randomUUID(), op, "cabinet-01", batch.id, grams)
  val correlator = new Correlator()

  "Correlation DataMatrix/poids" should {
    "produire le meme candidat dans les deux ordres sans modifier le stock" in {
      for (inverse <- Vector(false, true)) {
        val op = UUID.randomUUID()
        val inputs = if (inverse) Vector(weight(op), scan(op)) else Vector(scan(op), weight(op))
        val first = correlator.accept(CorrelationState(), inputs.head, product, batch, 0)
        assert(first._2 == Right(None))
        val next = correlator.accept(first._1, inputs.last, product, batch, 100)
        assert(next._2 == Right(Some(StockCandidate(op, batch.id, -1))))
        assert(batch.quantity == 40)
      }
    }
    "distinguer ajout et retrait, refuser les masses incoherentes" in {
      val op = UUID.randomUUID()
      val first = correlator.accept(CorrelationState(), scan(op), product, batch, 0)._1
      assert(
        correlator.accept(first, weight(op, 20), product, batch, 100)._2 == Right(
          Some(StockCandidate(op, batch.id, 2))
        )
      )
      assert(correlator.accept(first, weight(op, -13), product, batch, 100)._2.isLeft)
      assert(correlator.accept(first, weight(op, Double.NaN), product, batch, 100)._2.isLeft)
    }
    "ne pas fusionner deux operations ni des observations expirees" in {
      val op = UUID.randomUUID()
      val first = correlator.accept(CorrelationState(), scan(op), product, batch, 0)._1
      assert(
        correlator.accept(first, weight(UUID.randomUUID()), product, batch, 100)._2 == Right(None)
      )
      assert(correlator.accept(first, weight(op), product, batch, 5001)._2 == Right(None))
      val future = correlator.accept(CorrelationState(), scan(op), product, batch, 6000)._1
      assert(correlator.accept(future, weight(op), product, batch, 0)._2 == Right(None))
    }
    "dedupliquer et rejeter une identite ou une operation ambigue" in {
      val op = UUID.randomUUID()
      val input = scan(op)
      val first = correlator.accept(CorrelationState(), input, product, batch, 0)._1
      assert(correlator.accept(first, input, product, batch, 100)._2 == Right(None))
      assert(
        correlator.accept(first, input.copy(dataMatrix = "changed"), product, batch, 100)._2.isLeft
      )
      assert(correlator.accept(first, scan(op), product, batch, 100)._2.isLeft)
    }
  }
}
