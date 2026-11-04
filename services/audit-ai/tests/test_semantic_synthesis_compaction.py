"""Lossless synthesis capacity: real-sized metadata, no provider or database IO."""
import copy
from dataclasses import replace
import hashlib
import json

import httpx
import pytest

from audit_ai.config import Settings
from audit_ai.errors import AuditAiError
from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
from audit_ai.model.gateway import ContractTextOperation, OpenAiCompatibleModelGateway
from audit_ai.model.router import ContractChunkResult, ContractEvidenceItem, ContractHint
from audit_ai.model.semantic_work import _JournaledApplication, checkpoint_hash


FORMAT = "contract-synthesis-evidence-table-v1"


def fixture():
    segments = []
    for index in range(39):
        text = f"第{index + 1}段完整交付条款；同名材料独立，尾部义务不得遗漏😀。"
        segments.append(EvidenceSegment(
            evidence_id=f"C:S{index + 1}",
            file_id=f"00000000-0000-4000-8000-{index % 8 + 1:012d}",
            material_category="CONTRACT_BODY" if index % 2 == 0 else "BID_RESPONSE",
            source_start=index * 100, source_end=index * 100 + len(text),
            exact_text=text, citable=True,
        ))
    results, ordinal = [], 0
    for unit, count in enumerate([8, 4, 5, 1, 4, 1, 1, 2], 1):
        hints = []
        for local in range(count):
            # Repeated titles never imply the same finding or source identity.
            primary = [f"C:S{ordinal % 39 + 1}"]
            comparison = [f"C:S{(ordinal + 26) % 39 + 1}"]
            description = f"第{unit}块第{local + 1}条，按双方约定核对交付义务、服务期限及响应条件。完整描述保留，不把同名条款当同一事实。尾部关注{ordinal}😀。"
            hints.append(ContractHint(category="DELIVERY_TERMS", attention="REVIEW", title="同名条款逐项核对",
                description=description, evidence_refs=[], problem_evidence_ids=tuple(primary),
                comparison_evidence_ids=tuple(comparison)))
            ordinal += 1
        results.append(ContractChunkResult(summary=f"第{unit}块完整摘要；核对本块约定及跨材料差异，末尾{unit}😀。", hints=hints))
    return results, EvidenceCatalog(segments)


def expected_value(results, catalog):
    # Independent statement of the pre-existing semantic input, not encoder output.
    references = []
    partials = []
    for result in results:
        hints = []
        for hint in result.hints:
            item = {"category": hint.category, "attention": hint.attention, "title": hint.title,
                "description": hint.description, "problemEvidenceIds": list(hint.problem_evidence_ids),
                "comparisonEvidenceIds": list(hint.comparison_evidence_ids)}
            unavailable = [e.unavailable_material_category for e in (hint.evidence_items or [])
                           if e.unavailable_material_category is not None]
            if unavailable:
                item["unavailableMaterialCategory"] = unavailable[0]
            hints.append(item)
            for identity in (*hint.problem_evidence_ids, *hint.comparison_evidence_ids):
                if identity not in references:
                    references.append(identity)
        partials.append({"summary": result.summary, "hints": hints})
    evidence = []
    for identity in references:
        segment = catalog.by_id[identity]
        evidence.append({"evidenceId": identity, "fileId": segment.file_id,
            "materialCategory": segment.material_category, "citable": True,
            "sourceStart": segment.source_start, "sourceEnd": segment.source_end,
            "exactTextSha256": hashlib.sha256(segment.exact_text.encode("utf-8")).hexdigest()})
    return {"partialResults": partials, "evidenceCatalog": evidence, "omittedHintCount": 0}


def decode(value):
    if value.get("inputFormat") != FORMAT:
        return value
    evidence = []
    for row in value["evidenceCatalog"]["rows"]:
        item = dict(zip(value["evidenceCatalog"]["columns"], row, strict=True))
        item.update(value["fileRegistry"][item.pop("fileIndex")])
        evidence.append(item)
    return {"partialResults": value["partialResults"], "evidenceCatalog": evidence,
            "omittedHintCount": value["omittedHintCount"]}


def application():
    return _JournaledApplication(Settings(model_name="synthetic-model"), object())


def test_dense_eight_unit_26_hint_39_identity_source_fits_without_losing_any_field():
    results, catalog = fixture()
    expected = expected_value(results, catalog)
    assert len(json.dumps(expected, ensure_ascii=False, separators=(",", ":"))) > 12_000
    source, chars = application()._contract_synthesis_source(results, catalog)
    assert chars == len(source) <= 12_000
    value = json.loads(source)
    assert value["inputFormat"] == FORMAT
    assert value["partialResults"] == expected["partialResults"]
    assert len(value["evidenceCatalog"]["rows"]) == 39
    assert decode(value) == expected
    assert application()._contract_synthesis_source(results, catalog)[0] == source
    # Public persisted DTOs exclude these internal references; this test must
    # use the real in-memory identities retained by provider replay validation.
    assert "problem_evidence_ids" not in results[0].hints[0].model_dump()
    assert value["partialResults"][-1]["hints"][-1]["description"].endswith("尾部关注25😀。")


def test_same_name_and_repeated_text_never_merge_different_evidence_or_file_identities():
    results, catalog = fixture()
    first, second = catalog.segments[:2]
    catalog = EvidenceCatalog([first, replace(second, exact_text=first.exact_text,
        source_end=second.source_start + len(first.exact_text)), *catalog.segments[2:]])
    source, _ = application()._contract_synthesis_source(results, catalog)
    reconstructed = decode(json.loads(source))
    assert reconstructed == expected_value(results, catalog)
    assert len({item["evidenceId"] for item in reconstructed["evidenceCatalog"]}) == 39
    assert len({item["fileId"] for item in reconstructed["evidenceCatalog"]}) == 8
    assert sum(len(result["hints"]) for result in reconstructed["partialResults"]) == 26
    copied = {item["evidenceId"]: item for item in reconstructed["evidenceCatalog"]}
    assert copied["C:S1"]["exactTextSha256"] == copied["C:S2"]["exactTextSha256"]
    assert copied["C:S1"]["fileId"] != copied["C:S2"]["fileId"]


def test_typed_missing_material_category_and_full_non_monetary_text_are_unchanged():
    results, catalog = fixture()
    hint = results[0].hints[0]
    hint.attention = "INSUFFICIENT"
    hint.problem_evidence_ids = ()
    hint.description = "缺少完整通知材料；仍须逐项核对每周频次、百分之三上限及2026年交付期限。尾部说明😀。"
    hint.evidence_items = [ContractEvidenceItem(file_id=catalog.segments[0].file_id,
        status="INSUFFICIENT", unavailable_material_category="AWARD_NOTICE")]
    source, _ = application()._contract_synthesis_source(results, catalog)
    decoded = decode(json.loads(source))
    assert decoded == expected_value(results, catalog)
    item = decoded["partialResults"][0]["hints"][0]
    assert item["unavailableMaterialCategory"] == "AWARD_NOTICE"
    assert item["description"] == hint.description
    assert item["problemEvidenceIds"] == [] and item["comparisonEvidenceIds"] == ["C:S27"]


def test_small_source_keeps_the_existing_json_protocol_and_original_spans_hashes():
    results, catalog = fixture()
    results = [ContractChunkResult(summary=results[0].summary, hints=results[0].hints[:1])]
    source, chars = application()._contract_synthesis_source(results, catalog)
    assert chars == len(source) <= 12_000
    assert json.loads(source) == expected_value(results, catalog)
    assert "inputFormat" not in json.loads(source)


def test_uncompressible_complete_text_still_fails_closed_at_the_same_bound():
    results, catalog = fixture()
    results = [ContractChunkResult(summary=str(index) + "完整摘要" * 950, hints=result.hints)
               for index, result in enumerate(results)]
    before = copy.deepcopy(results)
    with pytest.raises(AuditAiError) as caught:
        application()._contract_synthesis_source(results, catalog)
    assert caught.value.code == "SEMANTIC_SYNTHESIS_INPUT_BOUND"
    assert results == before


def test_missing_referenced_identity_is_rejected_instead_of_omitted():
    results, catalog = fixture()
    catalog = EvidenceCatalog(catalog.segments[1:])
    with pytest.raises(AuditAiError) as caught:
        application()._contract_synthesis_source(results, catalog)
    assert caught.value.code == "SEMANTIC_CHECKPOINT_INVALID"


def test_policy_explicitly_freezes_the_compact_format_without_changing_shared_budgets():
    from test_semantic_work_units import setup
    service, gateway, _, request = setup()
    manifest = service._manifest(request)
    assert manifest["policy"]["synthesisInputFormat"] == FORMAT
    assert manifest["policy"]["synthesisPromptVersion"] == FORMAT
    assert manifest["synthesisMaxChars"] == 12_000
    assert manifest["timeLimitMs"] == 900_000 and manifest["unitTimeLimitMs"] == 300_000
    assert manifest["repairLimit"] == 2 and manifest["callLimit"] == len(manifest["units"]) + 2
    assert manifest["maxOutputTokensPerCall"] == 6144
    old_policy = {key: value for key, value in manifest["policy"].items()
                  if key not in {"synthesisInputFormat", "synthesisPromptVersion"}}
    assert checkpoint_hash(old_policy) != manifest["policyFingerprint"]
    old_manifest = {**manifest, "policy": old_policy, "policyFingerprint": checkpoint_hash(old_policy)}
    old_manifest.pop("planFingerprint")
    with pytest.raises(AuditAiError) as caught:
        service.advance(request, checkpoint_hash(old_manifest), [], None, remaining_ms=700_000, execute=False)
    assert caught.value.code == "SEMANTIC_CHECKPOINT_INVALID" and not gateway.operations


def test_actual_wire_explains_table_and_file_index_only_for_durable_synthesis():
    bodies = []
    def handler(wire):
        bodies.append(json.loads(wire.content))
        return httpx.Response(200, json={"model": "synthetic-model", "choices": [{
            "finish_reason": "stop", "message": {"content": '{"summary":"核对。","hints":[]}'}}]})
    gateway = OpenAiCompatibleModelGateway(Settings(model_name="synthetic-model", model_api_key="synthetic-key",
        model_base_url="https://provider.invalid/v1"), transport=httpx.MockTransport(handler))
    results, catalog = fixture()
    source, _ = application()._contract_synthesis_source(results, catalog)
    for purpose, policy in [("SYNTHESIZE", "contract-semantic-work-v3"),
                            ("ANALYZE", "contract-semantic-work-v3"), ("SYNTHESIZE", None)]:
        gateway.analyze_contract(ContractTextOperation(project_id="synthetic-project", chunk_index=8, chunk_count=8,
            source_text=source, purpose=purpose, generation_policy=policy))
    system = bodies[0]["messages"][0]["content"]
    assert FORMAT in system and "fileRegistry" in system and "fileIndex" in system and "columns" in system
    assert "Copy evidenceId verbatim" in system
    assert bodies[0]["messages"][1]["content"].endswith(source)
    assert bodies[0]["max_tokens"] == 6144 and bodies[0]["reasoning_effort"] == "none"
    assert bodies[0]["response_format"] == {"type": "json_object"}
    assert all(FORMAT not in body["messages"][0]["content"] for body in bodies[1:])
