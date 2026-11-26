import importlib.util
import json
from pathlib import Path

spec = importlib.util.spec_from_file_location("coverage_diag", Path(__file__).resolve().parents[3] / "tools/procurement-coverage/collect.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def test_counts_only_export_never_includes_context_or_names():
    line = ('private-company.docx 机密公司 password=secret '\
            'event=PIPELINE_STAGE_END operation=PROCUREMENT_AUTO stage=DOCUMENT_COVERAGE '\
            'resourceId=12345678-1234-1234-1234-123456789abc outcome=EXTRACTED '\
            'fileCount=4 outputChars=3200 factCount=7 inputChars=3000 callCount=1')
    result = module.collect([line, line.replace('fileCount=4', 'fileCount=5')], b'first-salt')
    assert len(result['documents']) == 1
    item = result['documents'][0]
    assert item['fileCount'] == 5
    assert item['outputChars'] == 3200
    rendered = json.dumps(result)
    for secret in ['机密公司', 'private-company', 'secret', '12345678']:
        assert secret not in rendered
    assert item['document'] != module.collect([line], b'second-salt')['documents'][0]['document']


def test_unknown_or_malicious_fields_are_not_exported():
    result = module.collect(['event=PIPELINE_STAGE_END operation=PROCUREMENT_AUTO stage=DOCUMENT_COVERAGE '
                             'resourceId=12345678-1234-1234-1234-123456789abc outcome=SecretName '
                             'fileName=SecretName outputChars=-1 inputChars=invalid'], b'salt')
    assert result['documents'][0]['status'] == 'OTHER'
    assert 'outputChars' not in result['documents'][0]
    assert 'SecretName' not in json.dumps(result)
