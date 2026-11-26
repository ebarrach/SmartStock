import unittest
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from calibration import calibrate


class CalibrationTests(unittest.TestCase):
    def test_cli_binds_device_and_preserves_existing_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            measurements = folder / 'measurements.csv'
            measurements.write_text('raw,grams\n100,0\n200,10\n300,20\n', encoding='utf-8')
            output = folder / 'profile.json'
            command = [sys.executable, str(Path(__file__).with_name('calibration.py')),
                       str(measurements), str(output)]
            missing = subprocess.run(command, capture_output=True)
            self.assertNotEqual(missing.returncode, 0)
            self.assertFalse(output.exists())
            command += ['--device-source', 'synthetic-device']
            subprocess.run(command, check=True, capture_output=True)
            original = output.read_bytes()
            self.assertEqual(json.loads(original)['deviceSource'], 'synthetic-device')
            repeated = subprocess.run(command, capture_output=True)
            self.assertNotEqual(repeated.returncode, 0)
            self.assertEqual(output.read_bytes(), original)

    def test_synthetic_linear_scale(self):
        model = calibrate([(100, 0), (200, 10), (300, 20)])
        self.assertAlmostEqual(model['gramsPerRaw'], 0.1)
        self.assertAlmostEqual(model['offsetGrams'], -10)
        self.assertAlmostEqual(model['residualRmsGrams'], 0)

    def test_missing_tare_or_degenerate_readings(self):
        for measurements in [[(100, 10), (200, 20)], [(100, 0), (100, 20)], [(100, 0), (float('nan'), 10)]]:
            with self.assertRaises(ValueError):
                calibrate(measurements)
