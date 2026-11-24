package smartstock.engine

/** Pure fan policy. Thresholds are project demonstration settings, not medical limits. */
object ThermalPolicy {
  val bands: Vector[(Double, Double)] =
    Vector(15.0 -> 0.0, 22.0 -> 25.0, 24.0 -> 50.0, 26.0 -> 75.0, 28.0 -> 100.0)
  val hysteresis: Double = 0.5

  def next(temperature: Double, previousPower: Double): Double = {
    require(temperature.isFinite, "Temperature must be finite")
    val nominal = bands.lastIndexWhere(_._1 <= temperature).max(0)
    val previous = bands.indexWhere(_._2 == previousPower)
    if (previous > nominal && temperature >= bands(previous)._1 - hysteresis)
      bands(previous)._2
    else bands(nominal)._2
  }
}
