import unittest
from gs1_decoder import decode_gs1, GS1Error

class GS1Tests(unittest.TestCase):
    def test_scanner_parentheses_and_compact(self):
        expected={'gtin':'05400711223344','lot':'LOT-A','serial':'SER-1','expiration':'2027-12-31'}
        self.assertEqual(decode_gs1('(01)05400711223344(17)271231(10)LOT-A(21)SER-1'),expected)
        self.assertEqual(decode_gs1(']d201054007112233441727123110LOT-A\x1d21SER-1'),expected)
    def test_last_day_of_month(self):
        self.assertEqual(decode_gs1('(01)05400711223344(17)280200(10)L(21)S')['expiration'],'2028-02-29')
    def test_invalid_or_ambiguous(self):
        for code in ['', '(01)123(17)271231(10)L(21)S','(01)05400711223344(17)271332(10)L(21)S','(01)05400711223344(17)271231(10)L']:
            with self.assertRaises(GS1Error):decode_gs1(code)
