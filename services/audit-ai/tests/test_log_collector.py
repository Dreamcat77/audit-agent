import importlib.util
import json
from pathlib import Path

spec=importlib.util.spec_from_file_location("ai_log_extract",Path(__file__).resolve().parents[3]/"tools/ai-log-extract.py")
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def test_raw_journal_and_nested_json_only_export_metadata():
    raw={"_SYSTEMD_UNIT":"audit-ai.service","__REALTIME_TIMESTAMP":"1791612000000000",
         "MESSAGE":"PROVIDER_HTTP_REJECTED request_id=synthetic-1 stage=MODEL_HTTP http_status=400 provider_error_kind=CONTEXT_LIMIT "
                   "prompt=BUSINESS_SENTINEL api_key=KEY_SENTINEL url=https://PRIVATE_HOST_SENTINEL.invalid"}
    result=module.extract(json.dumps(raw))
    assert result["http_status"]==400 and result["provider_error_kind"]=="CONTEXT_LIMIT"
    assert "SENTINEL" not in json.dumps(result)
    raw["MESSAGE"]=json.dumps({"message":"PIPELINE_STAGE_END stage=DB_COMPLETE_COMMIT reason=DATABASE_ERROR sqlErrorCode=-6004",
                                 "requestId":"synthetic-1","prompt":"BUSINESS_SENTINEL"})
    result=module.extract(json.dumps(raw))
    assert result["requestId"]=="synthetic-1" and result["sqlErrorCode"]==-6004
    assert "BUSINESS_SENTINEL" not in json.dumps(result)


def test_unrelated_stacktrace_or_untrusted_field_never_gets_exported():
    assert module.extract(json.dumps({"MESSAGE":"PASSWORD_SENTINEL jdbc:dm:PRIVATE_HOST_SENTINEL"})) is None
    result=module.extract(json.dumps({"MESSAGE":"PIPELINE_STAGE_END request_id=ok reason=https://KEY_SENTINEL.invalid"}))
    assert "reason" not in result


def test_adaptive_split_reason_and_reuse_events_export_without_any_material_content():
    for event in ('MATERIAL_CHUNK_SPLIT','SEMANTIC_CHUNK_SPLIT','MATERIAL_CHECKPOINT_REUSED'):
        result=module.extract(json.dumps({'_SYSTEMD_UNIT':'audit-ai.service','MESSAGE':
            event+' request_id=synthetic reason_code=INPUT_LIMIT chunk_index=3 chunk_count=9 input_chars=1800 '
            'prompt=BUSINESS_SENTINEL excerpt=PRIVATE_SENTINEL'}))
        assert result['event']==event and result['reason_code']=='INPUT_LIMIT'
        assert result['chunk_index']==3 and result['input_chars']==1800
        assert 'SENTINEL' not in json.dumps(result)


def test_correction_failure_and_shape_metadata_survive_export_without_model_text():
    message = "INFO audit_ai.model.router contract correction result rejected request_id=synthetic-repair " \
              "correction_failure_reason=CORRECTION_EVIDENCE_INVALID correction_mode=EVIDENCE_REPAIR " \
              "evidence_repair_target_count=2 returned_repair_count=1 prompt=BUSINESS_SENTINEL model=MODEL_SENTINEL"
    result = module.extract(json.dumps({"_SYSTEMD_UNIT":"audit-ai.service", "MESSAGE":message}))
    assert result["event"] == "CONTRACT_CORRECTION_REJECTED"
    assert result["correction_failure_reason"] == "CORRECTION_EVIDENCE_INVALID"
    assert result["evidence_repair_target_count"] == 2 and result["returned_repair_count"] == 1
    assert "SENTINEL" not in json.dumps(result)
