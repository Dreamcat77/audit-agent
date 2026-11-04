"""One provider dispatch per durable Java-owned unit; replay is validation, not inference."""
import copy
import json
import re
import threading
import time

import pytest

from audit_ai.config import Settings
from audit_ai.errors import AuditAiError
from audit_ai.model.gateway import ModelCompletion, ModelGatewayFailure
from audit_ai.model.router import ContractAnalysisRequest
from audit_ai.model.semantic_work import ContractSemanticWorkService, checkpoint_hash


class Gateway:
    def __init__(self, failure=None):
        self.operations = []
        self.failure = failure
        self.invalid_enum = False

    def analyze_contract(self, operation):
        self.operations.append(operation)
        if self.failure:
            raise self.failure
        ids = re.findall(r"\[evidenceId=([^;]+);", operation.source_text)
        if operation.purpose == "SYNTHESIZE":
            ids = [item["evidenceId"] for item in json.loads(operation.source_text)["evidenceCatalog"]]
        categories = operation.required_categories or ("CONTRACT_STRUCTURE",)
        completion = ModelCompletion(
            payload={"summary": "核对可见材料。", "hints": [
                {"category": category, "attention": "INFO", "title": "条款核对",
                 "description": "请结合所列材料核对约定。", "problemEvidenceIds": [],
                 "comparisonEvidenceIds": ids[:1], "unavailableMaterialCategory": None}
                for category in categories]},
            model="unit-model", duration_ms=12, finish_reason="STOP",
        )
        if self.invalid_enum:
            completion.payload["hints"][0]["attention"] = "INVALID_ATTENTION"
        return completion


def setup(failure=None):
    gateway = Gateway(failure)
    settings = Settings(model_name="unit-model", model_chunk_chars=650, model_max_chunks=30)
    request = ContractAnalysisRequest.model_validate({"projectId": "unit-project", "materials": [
        {"fileId": "body-file", "category": "CONTRACT_BODY",
         "text": ("项目系统维护，交付材料并提供服务；按双方约定验收。\n" * 85) + "末尾完整保留😀"},
        {"fileId": "award-file", "category": "AWARD_NOTICE", "text": "采购结果通知。"},
    ]})
    return ContractSemanticWorkService(settings, gateway), gateway, settings, request


def advance(service, request, plan, checkpoints, expected=None, execute=True):
    return service.advance(request, plan["planFingerprint"], checkpoints,
                           expected or plan["next"]["operationFingerprint"],
                           remaining_ms=800_000, execute=execute)


def typed_repair_checkpoint(service, gateway, request, plan):
    """A complete, parseable draft can need regeneration; transport errors cannot."""
    gateway.invalid_enum = True
    try:
        first = advance(service, request, plan, [])
    finally:
        gateway.invalid_enum = False
    assert first["checkpoint"]["status"] == "REPAIR_REQUIRED"
    assert first["checkpoint"]["call"]["safeReason"] == "ENUM_VALUE_INVALID"
    assert first["checkpoint"]["call"]["finishReason"] == "STOP"
    assert first["checkpoint"]["call"]["outcome"] == "FAILED"
    assert first["checkpoint"]["validationIssues"][0]["fieldPath"] == "hints[0].attention"
    assert first["next"] is not None and first["next"]["purpose"] == "REPAIR"
    assert len(gateway.operations) == 1
    return first


def test_plan_covers_every_original_codepoint_without_inference_or_changed_evidence_ids():
    service, gateway, _, request = setup()
    plan = service.plan(request)
    assert not gateway.operations
    assert plan["callLimit"] == len(plan["units"]) + plan["repairLimit"]
    assert plan["repairLimit"] == 2
    for material in request.materials:
        spans = [entry for entry in plan["evidence"] if entry["fileId"] == material.file_id]
        assert spans[0]["start"] == 0 and spans[-1]["end"] == len(material.text)
        assert all(left["end"] == right["start"] for left, right in zip(spans, spans[1:]))
        assigned = [identity for unit in plan["units"] for identity in unit["evidenceIds"]]
        assert all(assigned.count(span["evidenceId"]) == 1 for span in spans)
    assert plan["units"][-1]["purpose"] == "SYNTHESIZE"


def test_first_three_persisted_units_are_revalidated_after_restart_without_provider_replay():
    service, gateway, settings, request = setup()
    plan = service.plan(request)
    checkpoints = []
    expected = plan["next"]["operationFingerprint"]
    for _ in range(3):
        result = advance(service, request, plan, checkpoints, expected)
        assert result["checkpoint"]["status"] == "VERIFIED"
        checkpoints.append(result["checkpoint"])
        expected = result["next"]["operationFingerprint"]
    assert len(gateway.operations) == 3
    restarted_gateway = Gateway()
    restarted = ContractSemanticWorkService(settings, restarted_gateway)
    resumed = advance(restarted, request, plan, checkpoints, expected, execute=False)
    assert not restarted_gateway.operations and resumed["next"]["operationFingerprint"] == expected
    result = advance(restarted, request, plan, checkpoints, expected)
    assert len(restarted_gateway.operations) == 1
    assert result["checkpoint"]["logicalKey"] not in {item["logicalKey"] for item in checkpoints}


def test_synthesis_and_all_source_units_are_mandatory_before_final_result():
    service, gateway, _, request = setup()
    plan = service.plan(request)
    checkpoints = []
    expected = plan["next"]["operationFingerprint"]
    while True:
        result = advance(service, request, plan, checkpoints, expected)
        checkpoints.append(result["checkpoint"])
        if result["result"] is not None:
            break
        assert result["checkpoint"]["purpose"] != "SYNTHESIZE"
        expected = result["next"]["operationFingerprint"]
    assert len(gateway.operations) == len(plan["units"])
    assert checkpoints[-1]["purpose"] == "SYNTHESIZE"
    assert result["result"]["available"] is True


def test_synthesis_planning_bound_does_not_reclassify_the_validated_last_analysis(monkeypatch):
    from audit_ai.model import semantic_work
    service, gateway, settings, request = setup()
    plan = service.plan(request)
    def reject_synthesis(*_args, **_kwargs):
        raise AuditAiError(413, "SEMANTIC_SYNTHESIS_INPUT_BOUND", "synthetic capacity boundary")
    monkeypatch.setattr(semantic_work._JournaledApplication, "_contract_synthesis_source", reject_synthesis)
    checkpoints = []
    expected = plan["next"]["operationFingerprint"]
    for _ in range(plan["callLimit"]):
        result = advance(service, request, plan, checkpoints, expected)
        checkpoints.append(result["checkpoint"])
        if result["next"] is None:
            break
        expected = result["next"]["operationFingerprint"]
    assert result["failureCode"] == "SEMANTIC_SYNTHESIS_INPUT_BOUND"
    assert result["result"] is None
    assert len(gateway.operations) == len(plan["units"]) - 1
    assert all(packet["status"] == "VERIFIED" for packet in checkpoints)
    assert all(packet["call"]["outcome"] == "SUCCEEDED" for packet in checkpoints)
    assert all(packet["validatedResult"] for packet in checkpoints)
    restarted = ContractSemanticWorkService(settings, gateway)
    with pytest.raises(AuditAiError) as rejected:
        advance(restarted, request, plan, checkpoints, expected, execute=False)
    assert rejected.value.code == "SEMANTIC_SYNTHESIS_INPUT_BOUND"
    assert len(gateway.operations) == len(plan["units"]) - 1


def test_synthesis_bound_keeps_the_successful_validated_delta_repair(monkeypatch):
    from dataclasses import replace
    from audit_ai.model import semantic_work
    class DeltaGateway(Gateway):
        def analyze_contract(self, operation):
            if operation.purpose == "ANALYZE":
                completion = super().analyze_contract(operation)
                payload = copy.deepcopy(completion.payload)
                payload["hints"] = [hint for hint in payload["hints"] if hint["category"] != "CONTRACT_STRUCTURE"]
                return replace(completion, payload=payload)
            self.operations.append(operation)
            source = json.loads(operation.source_text)
            ids = re.findall(r"\[evidenceId=([^;]+);", source["originalOperationSource"])
            return ModelCompletion(payload={"missingCategoryHints": [{"category": "CONTRACT_STRUCTURE",
                "attention": "INFO", "title": "合同结构核对", "description": "按可见条款核对合同结构。",
                "problemEvidenceIds": [], "comparisonEvidenceIds": ids[:1]}]},
                model="unit-model", duration_ms=12, finish_reason="STOP")
    gateway = DeltaGateway()
    settings = Settings(model_name="unit-model", model_chunk_chars=650, model_max_chunks=30)
    request = ContractAnalysisRequest.model_validate({"projectId": "unit-project", "materials": [
        {"fileId": "body-file", "category": "CONTRACT_BODY", "text": "交付方式、验收与服务按约定执行。"}]})
    service = ContractSemanticWorkService(settings, gateway)
    plan = service.plan(request)
    first = advance(service, request, plan, [])
    assert first["checkpoint"]["status"] == "REPAIR_REQUIRED"
    def reject_synthesis(*_args, **_kwargs):
        raise AuditAiError(413, "SEMANTIC_SYNTHESIS_INPUT_BOUND", "synthetic capacity boundary")
    monkeypatch.setattr(semantic_work._JournaledApplication, "_contract_synthesis_source", reject_synthesis)
    expected = first["next"]["operationFingerprint"]
    repaired = advance(service, request, plan, [first["checkpoint"]], expected)
    packet = repaired["checkpoint"]
    assert packet["purpose"] == "REPAIR" and packet["call"]["outcome"] == "SUCCEEDED"
    assert packet["validatedResult"] is None and packet["correctedResult"]
    assert packet["status"] == "VERIFIED"
    assert repaired["failureCode"] == "SEMANTIC_SYNTHESIS_INPUT_BOUND"
    assert repaired["next"] is None and repaired["result"] is None
    assert len(gateway.operations) == 2
    with pytest.raises(AuditAiError) as rejected:
        advance(ContractSemanticWorkService(settings, gateway), request, plan,
                [first["checkpoint"], packet], expected, execute=False)
    assert rejected.value.code == "SEMANTIC_SYNTHESIS_INPUT_BOUND"
    assert len(gateway.operations) == 2


@pytest.mark.parametrize("mutation", ["text", "model", "checkpoint", "operation"])
def test_identity_or_payload_change_fails_before_any_new_dispatch(mutation):
    service, gateway, settings, request = setup()
    plan = service.plan(request)
    first = advance(service, request, plan, [])
    checkpoints = [copy.deepcopy(first["checkpoint"])]
    expected = first["next"]["operationFingerprint"]
    if mutation == "text":
        request = request.model_copy(update={"materials": [request.materials[0].model_copy(update={"text": "不同来源"}), request.materials[1]]})
    elif mutation == "model":
        settings = Settings(model_name="different-model", model_chunk_chars=650, model_max_chunks=30)
        service = ContractSemanticWorkService(settings, gateway)
    elif mutation == "checkpoint":
        checkpoints[0]["completion"]["payload"]["summary"] = "伪造结果"
        # Changing a stored blob cannot be accepted just because it still parses.
    else:
        expected = "0" * 64
    with pytest.raises(AuditAiError):
        advance(service, request, plan, checkpoints, expected)
    assert len(gateway.operations) == 1


@pytest.mark.parametrize("reason", ["PROVIDER_5XX", "TRANSPORT_OUTCOME_UNKNOWN"])
def test_first_provider_failure_stops_without_auto_retry_and_unknown_cannot_replay(reason):
    service, gateway, _, request = setup(ModelGatewayFailure(reason=reason, retryable=True, duration_ms=120_001))
    plan = service.plan(request)
    result = advance(service, request, plan, [])
    assert len(gateway.operations) == 1 and result["next"] is None and result["result"] is None
    assert result["checkpoint"]["status"] == ("UNKNOWN" if reason.endswith("UNKNOWN") else "FAILED")
    assert result["checkpoint"]["call"]["safeReason"] == reason
    with pytest.raises(AuditAiError):
        advance(service, request, plan, [result["checkpoint"]])
    assert len(gateway.operations) == 1


def test_expired_global_budget_never_dispatches_and_completion_hash_is_canonical():
    service, gateway, _, request = setup()
    plan = service.plan(request)
    with pytest.raises(AuditAiError):
        service.advance(request, plan["planFingerprint"], [], plan["next"]["operationFingerprint"], remaining_ms=0, execute=True)
    assert not gateway.operations
    assert checkpoint_hash({"a": 1, "b": 2}) == checkpoint_hash({"b": 2, "a": 1})


def test_slow_provider_has_hard_wall_deadline_and_retains_single_slot_until_late_exit():
    service, gateway, _, request = setup()
    entered, release = threading.Event(), threading.Event()
    original = gateway.analyze_contract
    def slow(operation):
        entered.set()
        release.wait(3)
        return original(operation)
    gateway.analyze_contract = slow
    plan = service.plan(request)
    try:
        started = time.monotonic()
        response = service.advance(request, plan["planFingerprint"], [], plan["next"]["operationFingerprint"], remaining_ms=35, execute=True)
        assert entered.is_set() and time.monotonic() - started < 0.5
        assert response["checkpoint"]["status"] == "UNKNOWN"
        assert response["checkpoint"]["call"]["safeReason"] == "TRANSPORT_OUTCOME_UNKNOWN"
        # A timed-out native/HTTP thread is NOT assumed cancelled or replaced.
        second = advance(service, request, plan, [])
        assert second["checkpoint"]["status"] == "FAILED"
        assert second["checkpoint"]["call"]["safeReason"] == "PROVIDER_CAPACITY_BUSY"
        assert response["result"] is None
    finally:
        release.set()


def test_real_http_work_routes_describe_without_inference_then_dispatch_exactly_one():
    from fastapi.testclient import TestClient
    from audit_ai.main import create_app
    service, gateway, settings, request = setup()
    client = TestClient(create_app(settings, model_gateway=gateway))
    plan_response = client.post("/ai/v1/agent/contract-semantic-work/plan", json=request.model_dump(by_alias=True))
    assert plan_response.status_code == 200
    plan = plan_response.json()
    assert gateway.operations == []
    body = {"request": request.model_dump(by_alias=True), "planFingerprint": plan["planFingerprint"],
            "checkpoints": [], "expectedOperationFingerprint": plan["next"]["operationFingerprint"],
            "remainingMs": 800_000, "execute": False}
    assert client.post("/ai/v1/agent/contract-semantic-work/advance", json=body).status_code == 200
    assert gateway.operations == []
    body["execute"] = True
    response = client.post("/ai/v1/agent/contract-semantic-work/advance", json=body)
    assert response.status_code == 200 and response.json()["checkpoint"]["status"] == "VERIFIED"
    assert len(gateway.operations) == 1


def test_preparation_and_checkpoint_revalidation_are_inside_the_same_wall_deadline(monkeypatch):
    service, gateway, _, request = setup()
    plan = service.plan(request)
    original = ContractSemanticWorkService._manifest
    def slow_prepare(self, body):
        time.sleep(0.06)
        return original(self, body)
    monkeypatch.setattr(ContractSemanticWorkService, "_manifest", slow_prepare)
    response = service.advance(request, plan["planFingerprint"], [], plan["next"]["operationFingerprint"],
                               remaining_ms=15, execute=True)
    assert not gateway.operations
    assert response["result"] is None and response["checkpoint"]["status"] == "FAILED"
    assert response["checkpoint"]["call"]["safeReason"] == "MODEL_TOTAL_BUDGET_EXHAUSTED"


def test_demo_safety_relaxation_is_not_an_eligible_durable_semantic_policy():
    from dataclasses import replace
    service, gateway, settings, request = setup()
    relaxed = ContractSemanticWorkService(replace(settings, demo_relax_breach_monetary=True), gateway)
    with pytest.raises(AuditAiError):
        relaxed.plan(request)
    assert not gateway.operations


def test_known_invalid_json_failure_stops_and_restart_cannot_resend_the_provider_call():
    service, gateway, settings, request = setup(ModelGatewayFailure(reason="INVALID_PROVIDER_JSON", retryable=True, duration_ms=1))
    plan = service.plan(request)
    first = advance(service, request, plan, [])
    assert first["checkpoint"]["status"] == "FAILED"
    assert first["checkpoint"]["call"]["safeReason"] == "INVALID_PROVIDER_JSON"
    assert first["next"] is None and first["result"] is None
    gateway.failure = None
    restarted = ContractSemanticWorkService(settings, gateway)
    for execute in (False, True):
        with pytest.raises(AuditAiError) as rejected:
            advance(restarted, request, plan, [first["checkpoint"]], execute=execute)
        assert rejected.value.code == "SEMANTIC_CHECKPOINT_INVALID"
    assert len(gateway.operations) == 1 and gateway.operations[0].transport_attempt == 1


@pytest.mark.parametrize("field,value", [("logicalKey", "0" * 64), ("ordinal", 999),
                                       ("purpose", "SYNTHESIZE"), ("inputFingerprint", "1" * 64),
                                       ("status", "REPAIR_REQUIRED")])
def test_hash_recomputed_checkpoint_must_still_match_rebuilt_identity_and_derived_validation(field, value):
    service, gateway, _, request = setup()
    plan = service.plan(request)
    first = advance(service, request, plan, [])
    checkpoint = copy.deepcopy(first["checkpoint"])
    checkpoint[field] = value
    checkpoint["checkpointHash"] = checkpoint_hash({key: child for key, child in checkpoint.items() if key != "checkpointHash"})
    with pytest.raises(AuditAiError):
        advance(service, request, plan, [checkpoint], first["next"]["operationFingerprint"], execute=False)
    assert len(gateway.operations) == 1


@pytest.mark.parametrize("stage", ["ANALYZE", "REPAIR", "SYNTHESIZE"])
def test_length_never_becomes_verified_even_when_json_and_required_categories_validate(stage):
    from dataclasses import replace
    service, gateway, _, request = setup()
    plan = service.plan(request)
    checkpoints = []
    expected = plan["next"]["operationFingerprint"]
    if stage == "REPAIR":
        first = typed_repair_checkpoint(service, gateway, request, plan)
        checkpoints.append(first["checkpoint"])
        expected = first["next"]["operationFingerprint"]
    original = gateway.analyze_contract
    def length(operation):
        completion = original(operation)
        if stage != "SYNTHESIZE" or operation.purpose == "SYNTHESIZE":
            return replace(completion, finish_reason="LENGTH")
        return completion
    gateway.analyze_contract = length
    for _ in range(plan["callLimit"]):
        result = advance(service, request, plan, checkpoints, expected)
        assert result["result"] is None
        if result["checkpoint"]["status"] == "FAILED":
            assert result["checkpoint"]["call"]["finishReason"] == "LENGTH"
            assert result["checkpoint"]["call"]["safeReason"] == "OUTPUT_TOKEN_LIMIT"
            assert result["next"] is None
            if stage == "REPAIR":
                assert result["checkpoint"]["purpose"] == "REPAIR"
                assert len(gateway.operations) == 2
                assert gateway.operations[-1].attempt == 2 and gateway.operations[-1].transport_attempt == 1
            break
        checkpoints.append(result["checkpoint"])
        expected = result["next"]["operationFingerprint"]
    else:
        pytest.fail("LENGTH was never rejected")


def test_thread_start_failure_releases_capacity_and_proves_no_provider_dispatch(monkeypatch):
    service, gateway, _, request = setup()
    plan = service.plan(request)
    with monkeypatch.context() as patch:
        patch.setattr("audit_ai.model.semantic_work.Thread.start", lambda _: (_ for _ in ()).throw(RuntimeError("thread unavailable")))
        response = advance(service, request, plan, [])
        assert response["checkpoint"]["status"] == "FAILED"
        assert response["checkpoint"]["call"]["safeReason"] == "PROVIDER_DISPATCH_NOT_STARTED"
        assert not gateway.operations
    assert advance(service, request, plan, [])["checkpoint"]["status"] == "VERIFIED"
    assert len(gateway.operations) == 1


@pytest.mark.parametrize("kind", ["ReadError", "RemoteProtocolError"])
def test_error_after_http_send_is_unknown_not_a_connect_failure_safe_to_replay(kind):
    import httpx
    from audit_ai.model.gateway import OpenAiCompatibleModelGateway
    service, _, settings, request = setup()
    from dataclasses import replace
    settings = replace(settings, model_base_url="https://unit.invalid/v1", model_api_key="unit-key")
    observed = []
    def respond(body):
        observed.append(body)
        raise getattr(httpx, kind)("safe simulated disconnected response")
    gateway = OpenAiCompatibleModelGateway(settings, transport=httpx.MockTransport(respond))
    service = ContractSemanticWorkService(settings, gateway)
    plan = service.plan(request)
    response = advance(service, request, plan, [])
    assert response["checkpoint"]["status"] == "UNKNOWN"
    assert response["checkpoint"]["call"]["safeReason"] == "TRANSPORT_OUTCOME_UNKNOWN"
    assert response["next"] is None and len(observed) == 1


def test_all_validated_analysis_concerns_survive_a_synthesis_that_only_returns_info():
    from dataclasses import replace
    service, gateway, _, request = setup()
    original = gateway.analyze_contract
    def concern(operation):
        response = original(operation)
        if operation.purpose == "ANALYZE" and operation.chunk_index == 1:
            evidence_id = re.findall(r"\[evidenceId=([^;]+);", operation.source_text)[0]
            payload = copy.deepcopy(response.payload)
            payload["hints"].append({"category": "OTHER_TEXT", "attention": "REVIEW", "title": "重要约定待核实",
                                     "description": "请核实交付约定。", "problemEvidenceIds": [evidence_id],
                                     "comparisonEvidenceIds": [], "unavailableMaterialCategory": None})
            return replace(response, payload=payload)
        return response
    gateway.analyze_contract = concern
    plan = service.plan(request)
    checkpoints, expected = [], plan["next"]["operationFingerprint"]
    for _ in range(plan["callLimit"]):
        response = advance(service, request, plan, checkpoints, expected)
        checkpoints.append(response["checkpoint"])
        if response["result"]:
            assert any(hint["title"] == "重要约定待核实" and hint["attention"] == "REVIEW" for hint in response["result"]["hints"])
            assert response["checkpoint"]["finalResult"] == response["result"]
            break
        expected = response["next"]["operationFingerprint"]
    else:
        pytest.fail("full semantic execution did not complete")


def test_oversized_synthesis_hint_collection_fails_instead_of_dropping_101st_concern():
    from dataclasses import replace
    service, gateway, _, request = setup()
    original = gateway.analyze_contract
    def oversized(operation):
        response = original(operation)
        if operation.purpose == "SYNTHESIZE":
            payload = copy.deepcopy(response.payload)
            payload["hints"] += [copy.deepcopy(payload["hints"][0]) for _ in range(101 - len(payload["hints"]))]
            payload["hints"][-1].update(attention="REVIEW", title="末尾不能丢失的关注",
                                         problemEvidenceIds=payload["hints"][-1]["comparisonEvidenceIds"], comparisonEvidenceIds=[])
            return replace(response, payload=payload)
        return response
    gateway.analyze_contract = oversized
    plan = service.plan(request)
    checkpoints, expected = [], plan["next"]["operationFingerprint"]
    for _ in range(plan["callLimit"]):
        response = advance(service, request, plan, checkpoints, expected)
        assert response["result"] is None
        if response["checkpoint"]["status"] == "FAILED":
            assert response["next"] is None
            break
        checkpoints.append(response["checkpoint"])
        expected = response["next"]["operationFingerprint"]
    else:
        pytest.fail("oversized collection was not rejected")


def test_v3_work_policy_has_finite_generation_budget_explicit_none_and_zero_transport_retries():
    import httpx
    from dataclasses import replace
    from audit_ai.model.gateway import OpenAiCompatibleModelGateway
    service, _, settings, request = setup()
    settings = replace(settings, model_base_url="https://unit.invalid/v1", model_api_key="unit-key")
    observed = []
    def respond(body):
        observed.append(json.loads(body.content))
        # Not a valid result: this test is transport proof, not semantic success.
        return httpx.Response(200, json={"model": "unit-model", "choices": [{"finish_reason": "stop", "message": {"content": "{}"}}]})
    service = ContractSemanticWorkService(settings, OpenAiCompatibleModelGateway(settings, transport=httpx.MockTransport(respond)))
    plan = service.plan(request)
    advance(service, request, plan, [])
    assert len(observed) == 1
    assert observed[0]["max_tokens"] == 6144
    assert observed[0]["reasoning_effort"] == "none"
    assert observed[0]["response_format"] == {"type": "json_object"}
    assert "COMPACT_COMPLETE_SEMANTIC_V2" in observed[0]["messages"][0]["content"]
    assert plan["policy"]["version"] == "contract-semantic-work-v3"
    assert plan["policy"]["reasoningEffort"] == "none"
    assert plan["policy"]["automaticTransportRetries"] == 0
    assert plan["policy"]["typedRepair"] == "FULL_REGENERATION"
    assert plan["maxOutputTokensPerCall"] == 6144
    assert plan["maxTotalOutputTokens"] == 6144 * plan["callLimit"]


@pytest.mark.parametrize("reason", ["PROVIDER_429", "INVALID_PROVIDER_RESPONSE"])
def test_v3_transport_failure_stops_and_reopen_cannot_resend_even_if_marked_retryable(reason):
    service, gateway, settings, request = setup(ModelGatewayFailure(reason=reason, retryable=True, duration_ms=1))
    plan = service.plan(request)
    first = advance(service, request, plan, [])
    assert first["checkpoint"]["status"] == "FAILED"
    assert first["checkpoint"]["call"]["safeReason"] == reason
    assert first["next"] is None and first["result"] is None
    gateway.failure = None
    restarted = ContractSemanticWorkService(settings, gateway)
    for execute in (False, True):
        with pytest.raises(AuditAiError) as rejected:
            advance(restarted, request, plan, [first["checkpoint"]], execute=execute)
        assert rejected.value.code == "SEMANTIC_CHECKPOINT_INVALID"
    assert len(gateway.operations) == 1 and gateway.operations[0].transport_attempt == 1


@pytest.mark.parametrize("stage", ["ANALYZE", "REPAIR", "SYNTHESIZE"])
def test_malformed_length_is_rejected_before_json_parse_and_usage_survives_failed_audit(stage):
    import httpx
    from dataclasses import replace
    from audit_ai.model.gateway import OpenAiCompatibleModelGateway
    service, gateway, settings, request = setup()
    settings = replace(settings, model_base_url="https://unit.invalid/v1", model_api_key="unit-key")
    sent = []
    def respond(body):
        sent.append(body)
        return httpx.Response(200, json={"model": "unit-model", "choices": [{"finish_reason": "length",
            "message": {"content": '{"summary":"incomplete"'}}],
            "usage": {"prompt_tokens": 100, "completion_tokens": 6144, "total_tokens": 6244}})
    external = OpenAiCompatibleModelGateway(settings, transport=httpx.MockTransport(respond))
    plan = service.plan(request)
    prefix, expected = [], plan["next"]["operationFingerprint"]
    if stage == "REPAIR":
        first = typed_repair_checkpoint(service, gateway, request, plan)
        prefix.append(first["checkpoint"])
        expected = first["next"]["operationFingerprint"]
    original = gateway.analyze_contract
    gateway.analyze_contract = lambda operation: external.analyze_contract(operation) if (
        stage != "SYNTHESIZE" or operation.purpose == "SYNTHESIZE") else original(operation)
    for _ in range(plan["callLimit"]):
        response = advance(service, request, plan, prefix, expected)
        if sent:
            assert response["checkpoint"]["status"] == "FAILED" and response["next"] is None
            assert response["checkpoint"]["call"]["safeReason"] == "OUTPUT_TOKEN_LIMIT"
            assert response["checkpoint"]["call"]["finishReason"] == "LENGTH"
            assert response["checkpoint"]["call"]["completionTokens"] == 6144
            assert len(sent) == 1 and response["result"] is None
            break
        prefix.append(response["checkpoint"])
        expected = response["next"]["operationFingerprint"]
    else:
        pytest.fail("target stage did not execute")


@pytest.mark.parametrize("stage", ["ANALYZE", "A2", "REPAIR", "DELTA_REPAIR", "SYNTHESIZE"])
def test_completed_call_checkpoint_overflow_is_small_proven_failure_not_unknown(stage):
    from dataclasses import replace
    from audit_ai.model.semantic_work import MAX_CHECKPOINT_BYTES

    class DenseGateway(Gateway):
        def analyze_contract(self, operation):
            target = ((stage == "ANALYZE" and operation.chunk_index == 1)
                      or (stage == "A2" and operation.chunk_index == 2)
                      or (stage == "REPAIR" and operation.attempt == 2)
                      or (stage == "DELTA_REPAIR" and operation.purpose not in {"ANALYZE", "SYNTHESIZE"})
                      or (stage == "SYNTHESIZE" and operation.purpose == "SYNTHESIZE"))
            if stage == "REPAIR" and not self.operations:
                completion = super().analyze_contract(operation)
                payload = copy.deepcopy(completion.payload)
                payload["hints"][0]["attention"] = "INVALID_ATTENTION"
                return replace(completion, payload=payload, model="synthetic-model", duration_ms=1)
            completion = super().analyze_contract(operation)
            ids = re.findall(r"\[evidenceId=([^;]+);", operation.source_text)
            if stage == "DELTA_REPAIR" and target:
                ids = re.findall(r"\[evidenceId=([^;]+);", json.loads(operation.source_text)["originalOperationSource"])
            if operation.purpose == "SYNTHESIZE":
                ids = [item["evidenceId"] for item in json.loads(operation.source_text)["evidenceCatalog"]]
            categories = operation.required_categories or ("OTHER_TEXT",)
            payload = {"summary": "Synthetic observation.", "hints": [
                    {"category": categories[i % len(categories)], "attention": "INFO",
                     "title": "Source observation " + str(i),
                     "description": "Please review the supplied source terms.",
                     "problemEvidenceIds": [], "comparisonEvidenceIds": ids if target or stage == "SYNTHESIZE" else ids[:1],
                     "unavailableMaterialCategory": None} for i in range(65 if target else len(categories))]}
            if stage == "DELTA_REPAIR":
                if target:
                    payload = {"missingCategoryHints": payload["hints"]}
                else:
                    payload["hints"] = [hint for hint in payload["hints"] if hint["category"] != "CONTRACT_STRUCTURE"]
            return replace(completion, payload=payload, model="synthetic-model", duration_ms=1234,
                           prompt_tokens=100, completion_tokens=200, total_tokens=300)

    materials = [{"fileId": "synthetic-file-" + str(i), "category": "CONTRACT_BODY",
                  "text": ("Delivery and acceptance terms apply. " * 26)[:900]} for i in range(4)]
    if stage in {"ANALYZE", "A2"}:
        materials.append({"fileId": "synthetic-tail", "category": "CONTRACT_BODY",
                          "text": ("Delivery terms apply. " * 3000)[:32000]})
    if stage == "A2":
        materials.insert(0, {"fileId": "synthetic-first", "category": "CONTRACT_BODY",
                             "text": ("Delivery terms apply. " * 300)[:5400]})
    request = ContractAnalysisRequest.model_validate({"projectId": "synthetic-project", "materials": materials})
    gateway = DenseGateway()
    service = ContractSemanticWorkService(Settings(model_name="synthetic-model"), gateway)
    plan = service.plan(request)
    if stage == "ANALYZE":
        assert len(plan["units"]) == 9 and plan["callLimit"] == 11 and plan["timeLimitMs"] == 900_000
    prefix, expected = [], plan["next"]["operationFingerprint"]
    for _ in range(plan["callLimit"]):
        original_prefix = copy.deepcopy(prefix)
        response = advance(service, request, plan, prefix, expected)
        assert prefix == original_prefix
        packet = response["checkpoint"]
        if response["failureCode"] == "SEMANTIC_CHECKPOINT_OUTPUT_BOUND":
            assert packet["status"] == "FAILED"
            assert packet["purpose"] == ("ANALYZE" if stage == "A2" else "REPAIR" if stage == "DELTA_REPAIR" else stage)
            assert response["next"] is None and response["result"] is None
            assert len(json.dumps(packet, ensure_ascii=False).encode("utf-8")) < MAX_CHECKPOINT_BYTES
            assert packet["checkpointHash"] == checkpoint_hash({k: v for k, v in packet.items() if k != "checkpointHash"})
            assert not any(packet.get(field) for field in ("completion", "validatedResult", "correctedResult", "finalResult"))
            assert packet["call"]["outcome"] == "FAILED"
            assert packet["call"]["safeReason"] == "SEMANTIC_CHECKPOINT_OUTPUT_BOUND"
            assert packet["call"]["model"] == "synthetic-model"
            assert packet["call"]["durationMs"] == 1234
            assert packet["call"]["finishReason"] == "STOP"
            assert [packet["call"][key] for key in ("promptTokens", "completionTokens", "totalTokens")] == [100, 200, 300]
            assert packet["call"]["ordinal"] == len(gateway.operations)
            assert len(gateway.operations) == len(prefix) + 1 <= plan["callLimit"]
            with pytest.raises(AuditAiError):
                advance(service, request, plan, [*prefix, packet], execute=False)
            assert len(gateway.operations) == len(prefix) + 1
            break
        assert response["next"] is not None and response["result"] is None, (response["failureCode"], packet.get("call"))
        prefix.append(packet)
        expected = response["next"]["operationFingerprint"]
    else:
        pytest.fail("target checkpoint did not overflow")


@pytest.mark.parametrize("proof", ["UNKNOWN", "SENT", "missing_call", "missing_completion"])
def test_checkpoint_overflow_never_fabricates_completed_call_proof(monkeypatch, proof):
    from audit_ai.model import semantic_work
    service, gateway, _, request = setup(
        ModelGatewayFailure(reason="TRANSPORT_OUTCOME_UNKNOWN", retryable=False, duration_ms=120001)
        if proof == "UNKNOWN" else None)
    plan = service.plan(request)
    record = semantic_work._ReplayGateway.record
    observed = []
    def incomplete(replay, call):
        try:
            return record(replay, call)
        finally:
            packet = replay.fresh
            if proof == "SENT":
                packet["status"] = "SENT"
            elif proof == "missing_call":
                packet.pop("call", None)
            elif proof == "missing_completion":
                packet.pop("completion", None)
            packet["syntheticOversizedDiagnostic"] = "x" * semantic_work.MAX_CHECKPOINT_BYTES
            observed.append(packet)
    monkeypatch.setattr(semantic_work._ReplayGateway, "record", incomplete)
    with pytest.raises(AuditAiError) as rejected:
        advance(service, request, plan, [])
    assert rejected.value.code == "SEMANTIC_CHECKPOINT_OUTPUT_BOUND"
    assert len(gateway.operations) == 1
    assert not any(packet.get("call", {}).get("safeReason") == "SEMANTIC_CHECKPOINT_OUTPUT_BOUND" for packet in observed)
    if proof in {"UNKNOWN", "SENT"}:
        assert observed[-1]["status"] == proof


@pytest.mark.parametrize("finish", ["LENGTH", "CONTENT_FILTER"])
def test_large_already_failed_completion_keeps_original_failure_classification(finish):
    from dataclasses import replace
    from audit_ai.model.semantic_work import MAX_CHECKPOINT_BYTES
    service, gateway, _, request = setup()
    original = gateway.analyze_contract
    large = False
    def completed(operation):
        completion = original(operation)
        payload = {"syntheticPadding": "x" * MAX_CHECKPOINT_BYTES} if large else completion.payload
        return replace(completion, payload=payload, finish_reason=finish,
                       prompt_tokens=100, completion_tokens=200, total_tokens=300)
    gateway.analyze_contract = completed
    plan = service.plan(request)
    baseline = advance(service, request, plan, [])
    large = True
    bounded = advance(service, request, plan, [])
    assert bounded["failureCode"] == baseline["failureCode"]
    assert bounded["checkpoint"]["call"] == baseline["checkpoint"]["call"]
    assert bounded["checkpoint"]["status"] == "FAILED"
    assert bounded["next"] is None and bounded["result"] is None
    assert not bounded["checkpoint"].get("completion")


def test_oversized_repair_required_stops_without_overwriting_known_validation_reason():
    from dataclasses import replace
    from audit_ai.model.semantic_work import MAX_CHECKPOINT_BYTES
    service, gateway, _, request = setup()
    request = ContractAnalysisRequest.model_validate({"projectId": "unit-project", "materials": [
        {"fileId": "body-file", "category": "CONTRACT_BODY", "text": "交付方式、验收与服务按约定执行。"}]})
    original = gateway.analyze_contract
    large = False
    def missing_category(operation):
        completion = original(operation)
        payload = copy.deepcopy(completion.payload)
        payload["hints"] = [hint for hint in payload["hints"] if hint["category"] != "CONTRACT_STRUCTURE"]
        payload["summary"] = 123  # known TYPE_MISMATCH before any repair dispatch
        if large:
            payload["summary"] = "x" * MAX_CHECKPOINT_BYTES
        return replace(completion, payload=payload)
    gateway.analyze_contract = missing_category
    plan = service.plan(request)
    baseline = advance(service, request, plan, [])
    assert baseline["checkpoint"]["status"] == "REPAIR_REQUIRED"
    assert baseline["checkpoint"]["call"]["safeReason"] is not None
    large = True
    bounded = advance(service, request, plan, [])
    assert bounded["checkpoint"]["call"] == baseline["checkpoint"]["call"]
    assert bounded["checkpoint"]["status"] == "FAILED"
    assert bounded["failureCode"] == "SEMANTIC_CHECKPOINT_OUTPUT_BOUND"
    assert bounded["next"] is None and bounded["result"] is None
    assert not bounded["checkpoint"].get("completion")
