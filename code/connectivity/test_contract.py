import json
import os
import unittest
from pathlib import Path
from urllib.request import urlopen
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate

ROOT = Path(__file__).resolve().parents[1] / 'shared' / 'api'


class ContractTests(unittest.TestCase):
    def setUp(self):
        self.spec = json.loads((ROOT / 'openapi.yaml').read_text(encoding='utf-8'))

    def validator(self, name):
        return Draft202012Validator({'$ref': '#/components/schemas/' + name,
                                     'components': self.spec['components']}, format_checker=FormatChecker())

    def test_schema_and_mobile_example(self):
        validate(self.spec)
        self.assertEqual(self.spec['openapi'], '3.1.0')
        for schema in self.spec['components']['schemas'].values():
            Draft202012Validator.check_schema(schema)
        snapshot = json.loads((ROOT / 'examples/snapshot.json').read_text(encoding='utf-8'))
        self.validator('Snapshot').validate(snapshot)
        self.assertEqual({b['location'] for b in snapshot['batches']}, {'cabinet', 'bag'})

    @unittest.skipUnless(os.environ.get('SMARTSTOCK_API_URL'), 'Requires running real Scala API')
    def test_live_agent_matches_shared_mobile_contract(self):
        with urlopen(os.environ['SMARTSTOCK_API_URL'] + '/api/v1/snapshot', timeout=5) as response:
            self.validator('Snapshot').validate(json.load(response))

    def test_demo_reports_catalog_and_snapshot_match_contract(self):
        fixtures = ROOT / 'examples'
        self.validator('Snapshot').validate(json.loads((fixtures / 'demo-snapshot.json').read_text(encoding='utf-8')))
        self.validator('Catalog').validate(json.loads((fixtures / 'demo-catalog.json').read_text(encoding='utf-8')))
        reports = json.loads((fixtures / 'demo-analytics.json').read_text(encoding='utf-8'))
        for report in reports.values():
            self.validator('AnalysisReport').validate(report)
        self.assertTrue(any(p['suggestion']['quantity'] > 0 for p in reports['6']['products']))
        self.assertTrue(any(p['suggestion']['quantity'] == 0 for p in reports['6']['products']))
        self.assertTrue(any(d['mean'] is None and d['count'] == 0 for d in reports['6']['sensors']))

    @unittest.skipUnless(os.environ.get('SMARTSTOCK_API_URL'), 'Requires running real Scala API')
    def test_live_catalog_and_analytics(self):
        for route, schema in [('catalog', 'Catalog'), ('analytics?months=6', 'AnalysisReport')]:
            with urlopen(os.environ['SMARTSTOCK_API_URL'] + '/api/v1/' + route, timeout=10) as response:
                self.validator(schema).validate(json.load(response))


if __name__ == '__main__':
    unittest.main()
