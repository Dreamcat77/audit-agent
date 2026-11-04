"""Regression coverage for the isolated support migration projection."""
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('support_manifest', Path(__file__).with_name('build_support_manifest.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class SupportManifestRegression(unittest.TestCase):
    def test_original_eight_table_group_is_unchanged(self):
        previous = json.loads((ROOT / 'docs/automatic-procurement-deploy26-20261027/evidence/pilot-support-schema-v3.json').read_text(encoding='utf-8'))
        self.assertEqual(module.build()['groups'][0], previous['groups'][0])

    def test_ledger_is_independent_of_existing_support_group(self):
        group = module.build()['groups'][1]
        self.assertEqual([t['name'] for t in group['tables']], ['MATERIAL_EXTRACTION_LEDGER'])
        self.assertEqual(len(group['statements']), 1)
        self.assertIn('DEADLINE_AT TIMESTAMP', group['statements'][0])
        self.assertNotIn('DROP ', group['statements'][0])

    def test_check_constraint_whitespace_does_not_change_semantics(self):
        self.assertEqual(module.key('CONSTRAINT CK_X CHECK(A > 0)'), module.key('CONSTRAINT CK_X CHECK (A > 0)'))

if __name__ == '__main__':
    unittest.main(verbosity=2)
