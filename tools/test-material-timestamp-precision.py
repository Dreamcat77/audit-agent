"""Offline regression for the deployed-report comparison, without HTTP or credentials."""
import ast
from pathlib import Path
import unittest

tree=ast.parse(Path(__file__).with_name('test-deployed-reliability26.py').read_text(encoding='utf-8'))
selected=[node for node in tree.body if isinstance(node,ast.ImportFrom) and node.module=='datetime'
          or isinstance(node,ast.Import) and any(alias.name=='re' for alias in node.names)
          or isinstance(node,ast.FunctionDef) and node.name=='dm8_timestamp']
namespace={}
exec(compile(ast.Module(body=selected,type_ignores=[]),'<isolated-timestamp-comparator>','exec'),namespace)
convert=namespace['dm8_timestamp']


class TimestampPrecisionTest(unittest.TestCase):
    def test_observed_dm8_rounding_is_equivalent(self):
        self.assertEqual(convert('2026-10-19T00:00:00.001512626Z'),convert('2026-10-19T00:00:00.001513Z'))
        self.assertEqual(convert('2026-10-19T00:00:00.999999999Z'),convert('2026-10-19T00:00:01Z'))
        self.assertEqual(convert('2026-10-19T08:00:00.001512626+08:00'),convert('2026-10-19T00:00:00.001513Z'))

    def test_different_microsecond_or_second_is_not_equivalent(self):
        self.assertNotEqual(convert('2026-10-19T00:00:00.001512499Z'),convert('2026-10-19T00:00:00.001513Z'))
        self.assertNotEqual(convert('2026-10-19T00:00:00.001512626Z'),convert('2026-10-19T00:00:00.001514Z'))
        self.assertNotEqual(convert('2026-10-19T00:00:00.001512626Z'),convert('2026-10-19T00:00:01.001513Z'))


if __name__=='__main__':unittest.main()
