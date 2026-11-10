package smartstock

import org.scalatest.wordspec.AnyWordSpec
import smartstock.engine.ThermalPolicy

class ThermalPolicySpec extends AnyWordSpec {
  "Politique thermique Scala" should {
    "respecter les seuils et lhysteresis de descente" in {
      assert(ThermalPolicy.next(20, 0) == 0)
      assert(ThermalPolicy.next(23, 0) == 25)
      assert(ThermalPolicy.next(25, 25) == 50)
      assert(ThermalPolicy.next(27, 50) == 75)
      assert(ThermalPolicy.next(30, 75) == 100)
      assert(ThermalPolicy.next(25.9, 75) == 75)
      assert(ThermalPolicy.next(25.5, 75) == 75)
      assert(ThermalPolicy.next(25.4, 75) == 50)
      assert(ThermalPolicy.next(21.9, 25) == 25)
      assert(ThermalPolicy.next(21.5, 25) == 25)
      assert(ThermalPolicy.next(21.4, 25) == 0)
      assert(ThermalPolicy.next(-10, 100) == 0)
      assert(ThermalPolicy.next(50, 0) == 100)
    }
    "refuser les temperatures non finies" in {
      Vector(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity).foreach { value =>
        intercept[IllegalArgumentException](ThermalPolicy.next(value, 0))
      }
    }
  }
}
