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


if __name__ == '__main__':
    unittest.main()
