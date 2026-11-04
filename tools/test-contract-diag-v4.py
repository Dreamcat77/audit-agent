"""Verify diagnostic projection keeps technical reasons and drops document data."""
import importlib.util
import json
import os
from pathlib import Path
import unittest

source = Path(os.environ.get('AUDIT_DIAG_SOURCE', str(
    Path(__file__).resolve().parents[3] / 'deliverables/ContractDiag_v4/collect.py')))
spec = importlib.util.spec_from_file_location('contract_diagnostic', source)
diagnostic = importlib.util.module_from_spec(spec)
spec.loader.exec_module(diagnostic)


class ProjectionTest(unittest.TestCase):
    def test_plain_schema_errors_preserve_all_known_fields_only(self):
        value = diagnostic.safe_event({'MESSAGE':
            'CONTRACT_ANALYSIS_ITEM_REJECTED stage=SCHEMA category=clauses item_index=2 '
            'validation_error_types=string_too_long,missing '
            'validation_error_fields=summary,citations,private_customer_field '
            'text=保密正文 filename=合同书.docx prompt=秘密提示'})
        self.assertEqual(value['validation_error_types'], ['missing', 'string_too_long'])
        self.assertEqual(value['validation_error_fields'], ['citations', 'summary'])
        self.assertEqual(value['item_index'], 2)
        self.assertEqual(value['category'], 'clauses')
        for private in ('保密正文', '合同书', '秘密提示', 'private_customer_field'):
            self.assertNotIn(private, json.dumps(value, ensure_ascii=False))

    def test_structured_provider_metrics_survive_without_payload(self):
        value = diagnostic.safe_event({'MESSAGE': json.dumps({
            'event': 'PROVIDER_HTTP_RESPONSE', 'operation': 'CONTRACT_CASE_ANALYSIS',
            'http_status': 200, 'output_bytes': 128, 'response_body': '机密正文',
            'url': 'http://private-host', 'token': 'secret-token'})})
        self.assertEqual(value['http_status'], 200)
        self.assertEqual(value['output_bytes'], 128)
        self.assertNotIn('机密正文', json.dumps(value, ensure_ascii=False))
        self.assertNotIn('private-host', json.dumps(value))
        self.assertNotIn('secret-token', json.dumps(value))

    def test_legacy_human_event_keeps_safe_provider_failure_metadata(self):
        value = diagnostic.safe_event({'MESSAGE': json.dumps({
            'event': 'model provider JSON rejected',
            'operation': 'CONTRACT_CASE_ANALYSIS', 'provider_finish_reason': 'LENGTH',
            'provider_content_chars': 1024, 'json_error_kind': 'EXPECTING_VALUE',
            'content': '原文及模型结果', 'exception': 'secret exception'})})
        self.assertEqual(value['event'], 'CONTRACT_PROVIDER_DIAGNOSTIC')
        self.assertEqual(value['provider_finish_reason'], 'LENGTH')
        self.assertEqual(value['provider_content_chars'], 1024)
        self.assertNotIn('content', value)
        self.assertNotIn('exception', value)

    def test_unknown_error_types_and_arbitrary_field_names_are_dropped(self):
        value = diagnostic.safe_event({'MESSAGE':
            'CONTRACT_ANALYSIS_ITEM_REJECTED validation_error_types=customer_secret '
            'validation_error_fields=customer_secret category=customer_secret'})
        self.assertNotIn('validation_error_types', value)
        self.assertNotIn('validation_error_fields', value)
        self.assertNotIn('category', value)

    def test_unrelated_free_text_is_not_exported(self):
        self.assertIsNone(diagnostic.safe_event({'MESSAGE': '秘密正文 任意文件名.pdf'}))


if __name__ == '__main__':
    unittest.main(verbosity=2)
