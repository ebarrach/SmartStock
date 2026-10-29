import json
import unittest
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1] / 'shared' / 'events'


class SchemaTests(unittest.TestCase):
    def setUp(self):
        self.schema = json.loads((ROOT / 'envelope.schema.json').read_text(encoding='utf-8'))
        self.validator = Draft202012Validator(self.schema, format_checker=FormatChecker())
        self.example = json.loads((ROOT / 'stock-changed.json').read_text(encoding='utf-8'))

    def test_schema_and_examples(self):
        Draft202012Validator.check_schema(self.schema)
        for name in ['stock-changed.json', 'ack.json']:
            self.validator.validate(json.loads((ROOT / name).read_text(encoding='utf-8')))

    def test_required_fields_and_unknown_fields(self):
        for field in self.schema['required']:
            bad = dict(self.example)
            del bad[field]
            self.assertFalse(self.validator.is_valid(bad), field)
        self.assertFalse(self.validator.is_valid(dict(self.example, unknown=True)))

    def test_invalid_metadata(self):
        for changes in [dict(v=2), dict(v=True), dict(id='invalid'), dict(seq=-1),
                        dict(seq=True), dict(src=' '), dict(type=''), dict(data=[]),
                        dict(ts='2026-10-29'), dict(corr='invalid')]:
            self.assertFalse(self.validator.is_valid(dict(self.example, **changes)), changes)


if __name__ == '__main__':
    unittest.main()
