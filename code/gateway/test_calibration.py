import unittest
from calibration import calibrate


class CalibrationTests(unittest.TestCase):
    def test_synthetic_linear_scale(self):
        model = calibrate([(100, 0), (200, 10), (300, 20)])
        self.assertAlmostEqual(model['gramsPerRaw'], 0.1)
        self.assertAlmostEqual(model['offsetGrams'], -10)
        self.assertAlmostEqual(model['residualRmsGrams'], 0)

    def test_missing_tare_or_degenerate_readings(self):
        for measurements in [[(100, 10), (200, 20)], [(100, 0), (100, 20)], [(100, 0), (float('nan'), 10)]]:
            with self.assertRaises(ValueError):
                calibrate(measurements)
