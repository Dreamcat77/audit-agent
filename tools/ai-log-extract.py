"""Export allowlisted diagnostic metadata from journal JSON, never raw messages."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import sys

COUNTS = set("durationMs duration_ms inputBytes input_bytes inputChars input_chars outputChars prompt_chars user_chars output_bytes "
             "segmentCount segment_count pageCount page_count rowCount row_count factCount fact_count itemCount item_count "
             "fileCount material_count chunk_index chunk_count httpStatus http_status byteLimit byte_limit textLimit text_limit "
             "connectTimeoutMs connect_timeout_ms readTimeoutMs read_timeout_ms responseBytes sqlErrorCode errorDepth stackLine "
             "prompt_tokens completion_tokens total_tokens max_output_tokens remaining_budget_ms attempt max_attempts "
             "provider_content_chars json_error_position validation_error_count missing_item_count duplicate_item_count "
             "foreign_item_count explicit_fact_count provider_call_count semantic_retry_count transport_retry_count "
             "accepted_hint_count filtered_hint_count evidence_segment_count citable_segment_count".split())
COUNTS.update({"chunk_char_limit", "max_chunks", "total_budget_ms"})
COUNTS.update("output_hint_count raw_hint_count normalized_hint_count safe_hint_count compact_chars "
              "transport_attempt max_transport_attempts non_citable_segment_count required_breach_segment_count "
              "required_breach_citable_segment_count hint_count missing_category_hint_count evidence_repair_count "
              "item_index problem_evidence_count comparison_evidence_count missing_category_count repair_target_count "
              "evidence_repair_target_count returned_missing_category_count returned_repair_count validation_error_index "
              "input_hint_count required_category_count".split())
TOKENS = set("event operation stage outcome reason gateway_reason parseMethod parse_method sqlState errorClass error_class "
             "rootCauseClass stackClass stackMethod provider_error_kind transport_error_kind transport response_format "
             "configuration_ready retryable provider_finish_reason provider_content_shape json_error_kind purpose "
             "validation_error_class correction_failure_reason final_model_rejection_reason semantic_defect_types".split())
TOKENS.update({"thinking_disabled", "response_content_kind", "reason_code"})
TOKENS.update("correction_mode correction_item_kind category attention category_valid_shape attention_valid_shape "
              "validation_actual_type validation_actual_enum validation_error_type filter_reason original_rejection_reason "
              "demo_relaxation summary_present hints_present missing_category_hints_present evidence_repairs_present "
              "has_title has_description has_problem_evidence_ids has_comparison_evidence_ids has_unavailable_material_category has_hint_id".split())
IDS = set("requestId request_id stageId call_id resourceId resource_id material_id document_id".split())
HASHES = {"endpoint_fingerprint", "model_fingerprint"}
PAIR = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*)=([^\s,\"}]+)")
EVENT = re.compile(r"\b(?:PIPELINE_STAGE_START|PIPELINE_STAGE_END|PIPELINE_START|PIPELINE_END|AI_HTTP_IN|AI_HTTP_OUT|"
                   r"PROVIDER_[A-Z_]+|MATERIAL_[A-Z_]+|BUDGET_[A-Z_]+|SEMANTIC_CHUNK_SPLIT|CONTRACT_CHUNKS_READY|OCR_START|OCR_END|OCR_UPLOAD_READY|REQUEST_SCHEMA_REJECTED)\b")
LEGACY_EVENTS = {
    "model request failed": "MODEL_FAILED",
    "model request completed": "MODEL_COMPLETED",
    "model provider JSON rejected": "MODEL_JSON_REJECTED",
    "model response shape observed": "MODEL_RESPONSE_SHAPE",
    "model hint shape observed": "MODEL_HINT_SHAPE",
    "model correction item shape observed": "MODEL_CORRECTION_SHAPE",
    "contract semantic analysis completed": "CONTRACT_ANALYSIS_COMPLETED",
    "contract evidence catalog prepared": "CONTRACT_EVIDENCE_CATALOG_READY",
    "contract correction result rejected": "CONTRACT_CORRECTION_REJECTED",
    "correction validation rejected": "CONTRACT_CORRECTION_SCHEMA_REJECTED",
    "correction validation error observed": "CONTRACT_CORRECTION_FIELD_REJECTED",
    "contract semantic result rejected": "CONTRACT_RESULT_REJECTED",
    "contract semantic result filtered": "CONTRACT_RESULT_FILTERED",
    "contract semantic completeness required": "CONTRACT_COVERAGE_REQUIRED",
    "contract semantic coverage prepared": "CONTRACT_COVERAGE_READY",
    "contract semantic hint filtered": "CONTRACT_HINT_FILTERED",
    "contract semantic hint normalization completed": "CONTRACT_HINT_NORMALIZED",
    "contract model attempt failed": "CONTRACT_MODEL_ATTEMPT_FAILED",
}


def extract(line):
    try:
        outer = json.loads(line)
        message = outer.get("MESSAGE", "")
        if not isinstance(message, str): return None
        nested = {}
        if message.startswith("{"):
            try:
                nested = json.loads(message)
                message = nested.get("message", "")
            except (ValueError, TypeError): return None
        if not isinstance(message, str): return None
        event = EVENT.search(message)
        # These prior model events already use the strict allowlisted seam.
        legacy = next((event_name for text, event_name in LEGACY_EVENTS.items() if text in message), None)
        if not event and not legacy: return None
        values = {**nested, **dict(PAIR.findall(message))}
        result = {"event": legacy or event.group(), "service":
                  "audit-ai" if outer.get("_SYSTEMD_UNIT") == "audit-ai.service" else "audit-backend"}
        stamp = outer.get("__REALTIME_TIMESTAMP", "")
        if isinstance(stamp, str) and stamp.isdigit():
            result["timestamp"] = datetime.fromtimestamp(int(stamp)/1_000_000,timezone.utc).isoformat()
        for key, value in values.items():
            raw = str(value)
            if key in COUNTS and re.fullmatch(r"-?[0-9]{1,15}", raw): result[key]=int(raw)
            elif key in IDS and re.fullmatch(r"[A-Za-z0-9._:-]{1,128}",raw): result[key]=raw
            elif key in HASHES and re.fullmatch(r"[0-9a-f]{24}",raw): result[key]=raw
            elif key in TOKENS and re.fullmatch(r"[A-Za-z0-9._-]{1,128}",raw): result[key]=raw
        return result
    except (ValueError, TypeError, AttributeError, OverflowError, OSError): return None


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--request-id",default="")
    args=parser.parse_args()
    if args.request_id and not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}",args.request_id): parser.error("invalid request ID")
    directory=Path(args.output)
    counts=Counter()
    exported=0
    truncated=False
    with (directory/"pipeline.jsonl").open("x",encoding="utf-8") as output:
        for line in sys.stdin:
            if len(line)>2_000_000: counts["SKIPPED_OVERSIZED_LOG_LINE"]+=1; continue
            record=extract(line)
            if record is None: continue
            if args.request_id and args.request_id not in {record.get("requestId"),record.get("request_id")}: continue
            counts[str(record.get("reason") or record.get("gateway_reason") or record.get("outcome") or "OBSERVED")]+=1
            output.write(json.dumps(record,ensure_ascii=False)+"\n")
            exported+=1
            if exported>=100000: truncated=True; break
    with (directory/"summary.json").open("x",encoding="utf-8") as output:
        json.dump({"exportedEvents":exported,"truncated":truncated,"requestId":args.request_id or None,
                   "counts":dict(counts),"rawMessagesIncluded":False},output,ensure_ascii=False,indent=2)
    print("DIAGNOSTIC_EVENTS="+str(exported))


if __name__=="__main__": main()
