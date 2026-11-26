import unittest
from uuid import uuid4
from correlated_capture import envelopes

class CaptureTests(unittest.TestCase):
    def setUp(self):
        self.op=str(uuid4())
        self.scan=dict(id=str(uuid4()),gtin='05400711223344',lot='LOT',serial='SER',expiration='2027-12-31',operationId=self.op,timestamp='2026-11-26T10:00:00Z')
        self.weight=dict(captureId=str(uuid4()),sensor='weight',event='exit',unit='raw',delta=-600,source='mkr-01',operationId=self.op)
        self.calibration=dict(gramsPerRaw=.01,offsetGrams=-2,deviceSource='mkr-01') # Synthetic test coefficients only.
        self.inventory=dict(products=[dict(id='p',dataMatrix=self.scan['gtin'])],batches=[dict(id=self.scan['gtin']+'-LOT',productId='p',location='cabinet',expiresOn=self.scan['expiration'])])
    def build(self,**changes):
        args=dict(operation=self.op,scan=self.scan,weight=self.weight,calibration=self.calibration,batch_id=self.scan['gtin']+'-LOT',observed_at='2026-11-26T10:00:02Z',inventory=self.inventory);args.update(changes)
        return envelopes(**args)
    def test_explicit_pair_stable_replay_and_units(self):
        first=self.build();self.assertEqual(first,self.build());self.assertEqual(first[1]['data']['grams'],-6)
        self.assertEqual(first[0]['data']['operationId'],first[1]['data']['operationId'])
        self.assertNotEqual(first[0]['id'],first[1]['id'])
    def test_missing_or_other_operation_refused(self):
        for weight in [dict(self.weight,operationId=''),dict(self.weight,operationId=str(uuid4()))]:
            with self.assertRaises(ValueError):self.build(weight=weight)
    def test_missing_calibration_or_wrong_device_refused(self):
        for calibration in [None,{},dict(self.calibration,deviceSource='other'),dict(self.calibration,gramsPerRaw=0)]:
            with self.assertRaises(ValueError):self.build(calibration=calibration)
    def test_other_batch_or_inventory_expiry_refused(self):
        with self.assertRaises(ValueError):self.build(batch_id='arbitrary-lot')
        wrong=dict(self.inventory,batches=[dict(self.inventory['batches'][0],expiresOn='2028-01-01')])
        with self.assertRaises(ValueError):self.build(inventory=wrong)
    def test_old_or_undated_capture_refused(self):
        for timestamp in ['2026-11-26T10:05:00Z','2026-11-26T10:00:02']:
            with self.assertRaises(ValueError):self.build(observed_at=timestamp)
    def test_explicit_legacy_id_mapping_without_renaming_stock(self):
        legacy=dict(self.inventory['batches'][0],id='legacy-id',lotCode='LOT')
        inventory=dict(self.inventory,batches=[legacy])
        self.assertEqual(self.build(inventory=inventory,batch_id='legacy-id')[0]['data']['batchId'],'legacy-id')
        with self.assertRaises(ValueError):
            self.build(inventory=dict(self.inventory,batches=[dict(legacy,lotCode=None)]),batch_id='legacy-id')
    def test_ambiguous_mapping_is_not_resolved_by_requested_id(self):
        duplicate=dict(self.inventory['batches'][0],id='other-id',lotCode='LOT')
        with self.assertRaises(ValueError):
            self.build(inventory=dict(self.inventory,batches=self.inventory['batches']+[duplicate]))
