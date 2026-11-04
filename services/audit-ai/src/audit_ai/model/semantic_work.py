"""One dispatch per Java-owned contract checkpoint; never a second workflow store.

The existing application validator is replayed against bounded private completions.
Replaying means deterministic validation ONLY, not calling the provider. Every
prefix must retain its plan, operation, result and validation hashes. A rejected
analysis may be retained solely as REPAIR_REQUIRED input to the existing delta
protocol; it is never exposed as a verified business result. Java owns all leases,
fences, deadlines, reservations, retries and durable publication.
"""
from __future__ import annotations

from dataclasses import asdict, replace
from concurrent.futures import Future, TimeoutError
from contextvars import copy_context
import hashlib
import json
import os
import re
from pathlib import Path
import time
from threading import Lock, Thread
from pydantic import Field, ValidationError

from audit_ai.errors import AuditAiError
from audit_ai.model import gateway as gateway_module
from audit_ai.model.gateway import ModelCompletion, ModelGatewayFailure, ModelTotalBudgetExhausted
from audit_ai.model.router import (ModelApplicationService, UnprocessableContractResult, _ContractProviderBudget,
    ContractHint, ContractChunkResult, _ContractHintNormalization, _SAFE_FILTERED_SUMMARY, _provider_text,
    _CorrectionOutcome, _CONTRACT_BODY_REQUIRED_CATEGORIES, _requires_quantified_breach_hint, _stable_required_categories)
from audit_ai.model.router import (_MONETARY_VALUE, _LABELLED_ABSOLUTE_NUMBER, _MONETARY_RATE_COMPARISON,
    _has_monetary_calculation, _has_unsafe_monetary_conclusion, _CONTRACT_CATEGORIES)
from audit_ai.model.contract_draft import ProviderValidationIssue, ProviderHintDraft
from audit_ai.model.contract_correction import assign_server_hint_ids
from audit_ai.model.semantic_quality import (PROTOCOL as QUALITY_PROTOCOL, WORK_CLAIM_TYPE_PROTOCOL,
    REGENERATION_CONTEXT_PROTOCOL, MAX_REGENERATION_CONTEXT_BYTES, MONEY_REPAIR_GUIDANCE_PROTOCOL, build_regeneration_context,
    QualityFailure, parse_scoped_draft, check_claims, reconcile_observations)


POLICY_VERSION = "contract-semantic-work-v3"
MAX_CHECKPOINT_BYTES = 262_144
MAX_CHECKPOINTS = 64
WORK_SCOPE_PROTOCOL = "contract-work-scope-witness-v1"
REPAIR_DIAGNOSTICS_PROTOCOL = "contract-bounded-repair-diagnostics-v1"
MAX_REPAIR_DIAGNOSTICS = 8
MONEY_OBSERVATION_PROTOCOL = "contract-money-observation-v1"
QUALITY_HINT_ENCODING = "contract-quality-hint-table-v1"
_UNKNOWN = frozenset({"READ_TIMEOUT", "TRANSPORT_OUTCOME_UNKNOWN", "TOTAL_BUDGET_EXCEEDED"})


def _json(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def checkpoint_hash(value):
    return hashlib.sha256(_json(value).encode("utf-8")).hexdigest()


class WorkScopeObservation(ContractHint):
    """Private carrier: scope witnesses are not public Finding evidence items."""
    work_scope_hash: str = Field(exclude=True, min_length=64, max_length=64)


def _work_scope(catalog, allowed_evidence_ids, scope_status):
    if not allowed_evidence_ids.issubset(catalog.by_id):
        raise _invalid()
    evidence = [{"evidenceId": segment.evidence_id, "fileId": segment.file_id,
        "category": segment.material_category, "start": segment.source_start, "end": segment.source_end,
        "citable": segment.citable, "textSha256": hashlib.sha256(segment.exact_text.encode("utf-8")).hexdigest()}
        for segment in catalog.segments if segment.evidence_id in allowed_evidence_ids]
    value = {"protocol": WORK_SCOPE_PROTOCOL, "scopeStatus": scope_status, "evidence": evidence,
             "checkedEvidenceIds": [row["evidenceId"] for row in evidence]}
    return {**value, "scopeHash": checkpoint_hash(value)}


def _synthesis_work_scopes(scopes):
    """Range manifest is readable; full ID/text hashes remain in checkpoints."""
    files, units = [], []
    for scope in scopes:
        ranges = []
        for entry in scope["evidence"]:
            file = {"fileId": entry["fileId"], "materialCategory": entry["category"]}
            if file not in files:
                files.append(file)
            row = [files.index(file), entry["start"], entry["end"], entry["citable"], 1]
            if ranges and ranges[-1][0] == row[0] and ranges[-1][2] == row[1] and ranges[-1][3] == row[3]:
                ranges[-1][2] = row[2]
                ranges[-1][4] += 1
            else:
                ranges.append(row)
        units.append([scope["scopeHash"], scope["scopeStatus"], ranges])
    return {"protocol": WORK_SCOPE_PROTOCOL, "fileRegistry": files,
            "unitColumns": ["scopeHash", "scopeStatus", "ranges"],
            "rangeColumns": ["fileIndex", "sourceStart", "sourceEnd", "citable", "segmentCount"], "units": units}


def _compact_synthesis_evidence(value):
    """Losslessly factor repeated catalog keys/file identity, never business text.

    Rows retain first-reference order and every evidence ID, citable bit, source
    span and text hash. Only the repeated (fileId, materialCategory) pair is
    interned, by exact identity rather than names, text or hash similarity.
    """
    files, file_indices, rows = [], {}, []
    for evidence in value["evidenceCatalog"]:
        identity = (evidence["fileId"], evidence["materialCategory"])
        if identity not in file_indices:
            file_indices[identity] = len(files)
            files.append({"fileId": identity[0], "materialCategory": identity[1]})
        rows.append([evidence["evidenceId"], file_indices[identity], evidence["citable"],
                     evidence["sourceStart"], evidence["sourceEnd"], evidence["exactTextSha256"]])
    return {"inputFormat": gateway_module.CONTRACT_SYNTHESIS_INPUT_FORMAT,
            "partialResults": value["partialResults"], "fileRegistry": files,
            "evidenceCatalog": {"columns": ["evidenceId", "fileIndex", "citable", "sourceStart",
                                             "sourceEnd", "exactTextSha256"], "rows": rows},
            "omittedHintCount": value["omittedHintCount"],
            **({key: value[key] for key in ("qualityRegistry", "workScopeRegistry") if key in value})}


def _compact_quality_hints(value):
    """Intern repeated keys, never change a value or collapse an observation."""
    layouts, partials = [], []
    for partial in value["partialResults"]:
        rows = []
        for hint in partial["hints"]:
            layout = list(hint)
            if layout not in layouts:
                layouts.append(layout)
            rows.append([layouts.index(layout), *hint.values()])
        partials.append({**partial, "hints": rows})
    return {**value, "partialResults": partials, "qualityHintEncoding": QUALITY_HINT_ENCODING, "hintLayouts": layouts}


def _invalid():
    return AuditAiError(409, "SEMANTIC_CHECKPOINT_INVALID", "semantic checkpoint identity is incompatible")


def _checkpoint_output_failure(packet, rejected, *, encoding_failure=False):
    """A completed provider call can fail local storage, but uncertainty cannot.

    Keep only the immutable operation and measured call receipt. In particular,
    none of the rejected completion or expanded results can become replay input.
    """
    completion, call = packet.get("completion"), packet.get("call")
    if (packet.get("status") not in {"VERIFIED", "REPAIR_REQUIRED", "FAILED"}
            or not isinstance(completion, dict) or not isinstance(call, dict)
            or call.get("outcome") not in {"SUCCEEDED", "FAILED"}
            or call.get("safeReason") in _UNKNOWN
            or (call["outcome"] == "FAILED" and not call.get("safeReason"))
            or completion.get("finish_reason") not in {"STOP", "LENGTH", "CONTENT_FILTER", "TOOL_CALLS"}
            or (packet["status"] != "FAILED" and completion["finish_reason"] != "STOP")
            or not isinstance(completion.get("payload"), dict)
            or type(call.get("ordinal")) is not int or call["ordinal"] < 1
            or type(call.get("durationMs")) is not int or call["durationMs"] < 0
            or any(call.get(target) != completion.get(source) for target, source in (
                ("provider", "provider"), ("model", "model"), ("durationMs", "duration_ms"),
                ("finishReason", "finish_reason"), ("promptTokens", "prompt_tokens"),
                ("completionTokens", "completion_tokens"), ("totalTokens", "total_tokens")))
            or not call.get("provider") or not call.get("model")):
        raise AuditAiError(502, "SEMANTIC_CHECKPOINT_OUTPUT_BOUND", "semantic work result exceeds bound")
    if encoding_failure and (packet["status"] != "FAILED" or call["outcome"] != "FAILED"):
        raise _invalid()
    failure = {key: packet[key] for key in (
        "logicalKey", "ordinal", "purpose", "operationFingerprint", "inputFingerprint")}
    failure.update({key: packet[key] for key in ("role", "groupingFingerprint") if key in packet})
    # A pre-existing known failure (e.g. LENGTH) keeps its primary reason.
    # Capacity is secondary metadata, never a replacement provider diagnosis.
    failure.update(status="FAILED", call=dict(call) if call["outcome"] == "FAILED" else {
                   **call, "outcome": "FAILED", "safeReason": "SEMANTIC_CHECKPOINT_OUTPUT_BOUND"})
    if encoding_failure:
        # There are no original UTF-8 bytes to count/hash. Never substitute an
        # escaped or replacement encoding and label it as the original bytes.
        failure["checkpointFailureCode"] = "SEMANTIC_CHECKPOINT_ENCODING"
        if packet.get("validationIssues"):
            failure["validationIssues"] = packet["validationIssues"]
    else:
        failure.update(checkpointFailureCode="SEMANTIC_CHECKPOINT_OUTPUT_BOUND",
                       rejectedCheckpointBytes=len(rejected),
                       rejectedCheckpointSha256=hashlib.sha256(rejected).hexdigest())
    failure["checkpointHash"] = checkpoint_hash(failure)
    if len(_json(failure).encode("utf-8")) > MAX_CHECKPOINT_BYTES:
        raise AuditAiError(502, "SEMANTIC_CHECKPOINT_OUTPUT_BOUND", "semantic work result exceeds bound")
    return failure


class _Boundary(Exception):
    def __init__(self, operation):
        self.operation = operation


class _ProviderStopped(Exception):
    pass


class _DeadlineGateway:
    """One in-flight thread, zero queue, hard caller deadline, no fake cancellation.

    A late transport may still be executing. It retains the capacity slot until
    it actually exits; its completion has no path to a returned checkpoint.
    """
    def __init__(self, delegate):
        self.delegate = delegate
        self.slot = Lock()

    def analyze_contract(self, operation):
        if not self.slot.acquire(blocking=False):
            raise ModelGatewayFailure(reason="PROVIDER_CAPACITY_BUSY", retryable=False, duration_ms=0)
        future = Future()
        context = copy_context()
        started = time.monotonic()
        def run():
            try:
                value = context.run(self.delegate.analyze_contract, operation)
            except BaseException as error:
                self.slot.release()
                future.set_exception(error)
            else:
                self.slot.release()
                future.set_result(value)
        try:
            Thread(target=run, name="contract-semantic-provider", daemon=True).start()
        except RuntimeError:
            self.slot.release()
            raise ModelGatewayFailure(reason="PROVIDER_DISPATCH_NOT_STARTED", retryable=False, duration_ms=0) from None
        try:
            return future.result(timeout=operation.provider_timeout_seconds)
        except TimeoutError:
            # No claim that the server stopped, and no second thread is launched.
            raise ModelGatewayFailure(reason="TRANSPORT_OUTCOME_UNKNOWN", retryable=False,
                                      duration_ms=max(0, round((time.monotonic() - started) * 1000))) from None


class _ReplayGateway:
    def __init__(self, owner, prior, expected, execute):
        self.owner = owner
        self.prior = prior
        self.expected = expected
        self.execute = execute
        self.cursor = 0
        self.fresh = None
        self.operation = None
        self.current = None
        self.normalized = None
        self.validation_issues = []
        self.quality = None
        self.monetary_observation = None

    def analyze_contract(self, operation):
        descriptor = self.owner._descriptor(operation)
        self.operation = operation
        self.normalized = None
        self.validation_issues = []
        self.quality = None
        self.monetary_observation = None
        if self.cursor < len(self.prior):
            prior = self.prior[self.cursor]
            self.cursor += 1
            if any(prior.get(key) != value for key, value in descriptor.items()):
                raise _invalid()
            self.current = prior
            completion = prior.get("completion")
            if prior["status"] not in {"VERIFIED", "REPAIR_REQUIRED"}:
                raise _invalid()
            if completion is None:
                call = prior.get("call", {})
                if prior["status"] != "REPAIR_REQUIRED" or call.get("safeReason") not in {
                    "INVALID_PROVIDER_JSON", "INVALID_PROVIDER_RESPONSE", "CONNECT_TIMEOUT", "CONNECT_ERROR", "PROVIDER_429",
                }:
                    raise _invalid()
                raise ModelGatewayFailure(reason=call["safeReason"], retryable=True, duration_ms=call["durationMs"])
            if completion.get("finish_reason") != "STOP":
                raise _invalid()
            return ModelCompletion(**completion)
        if self.fresh is not None or not self.execute:
            raise _Boundary(descriptor)
        if descriptor["operationFingerprint"] != self.expected:
            raise _invalid()
        if len(self.prior) >= self.owner.manifest["callLimit"]:
            raise AuditAiError(409, "SEMANTIC_CALL_BUDGET_EXHAUSTED", "semantic call budget is exhausted")
        self.current = None
        self.fresh = {**descriptor, "status": "SENT"}
        remaining = self.owner.deadline - time.monotonic()
        if remaining <= 0:
            raise ModelTotalBudgetExhausted("semantic work deadline expired before dispatch")
        operation = replace(operation, provider_timeout_seconds=min(operation.provider_timeout_seconds, remaining))
        # No replay or retry can cross this sole provider boundary twice.
        try:
            completion = self.owner.gateway.analyze_contract(operation)
        except ModelGatewayFailure as failure:
            self.fresh["failureMetadata"] = {name: getattr(failure, name) for name in (
                "finish_reason", "prompt_tokens", "completion_tokens", "total_tokens")}
            raise
        if not isinstance(completion, ModelCompletion):
            raise _invalid()
        self.fresh["completion"] = asdict(completion)
        self.owner.capture_synthetic_candidate(self,operation,completion)
        if completion.finish_reason != "STOP":
            raise ModelGatewayFailure(reason="OUTPUT_TOKEN_LIMIT" if completion.finish_reason == "LENGTH"
                                      else "INVALID_PROVIDER_RESPONSE", retryable=False,
                                      duration_ms=completion.duration_ms)
        return completion

    def record(self, call):
        value = call.model_dump(by_alias=True, exclude_none=True)
        packet = self.current if self.current is not None else self.fresh
        metadata = (packet.get("completion") or packet.get("failureMetadata")) if packet is not None else None
        if metadata is not None:
            for target, source in (("finishReason", "finish_reason"), ("promptTokens", "prompt_tokens"),
                                   ("completionTokens", "completion_tokens"), ("totalTokens", "total_tokens")):
                if metadata.get(source) is not None:
                    value[target] = metadata[source]
        # Ordinal is the deterministic full-plan dispatch ordinal, including repairs.
        if self.current is not None:
            if self.current["call"] != value:
                raise _invalid()
            if self.current.get("validatedResult") != self.normalized:
                raise _invalid()
            if self.current.get("validationIssues", []) != self.validation_issues:
                raise _invalid()
            if self.current.get("quality") != self.quality:
                raise _invalid()
            if self.current.get("monetaryObservation") != self.monetary_observation:
                raise _invalid()
            if (self.current["status"] == "VERIFIED") != (call.outcome == "SUCCEEDED"):
                raise _invalid()
            return
        if self.fresh is None:
            raise _invalid()
        self.fresh["call"] = value
        self.fresh["validatedResult"] = self.normalized
        if self.validation_issues:
            self.fresh["validationIssues"] = self.validation_issues
        if self.quality is not None:
            self.fresh["quality"] = self.quality
        if self.monetary_observation is not None:
            self.fresh["monetaryObservation"] = self.monetary_observation
        self.fresh["status"] = "VERIFIED" if call.outcome == "SUCCEEDED" else "FAILED"
        if call.safe_reason in _UNKNOWN:
            self.fresh["status"] = "UNKNOWN"
        # A concrete provider 5xx is a stop, not an automatic identical re-probe.
        if call.safe_reason in _UNKNOWN or call.safe_reason == "PROVIDER_5XX":
            raise _ProviderStopped()


class _JournaledApplication(ModelApplicationService):
    def __init__(self, settings, replay, quality_protocol=None):
        super().__init__(settings, replay, require_synthesis=True)
        self.replay = replay
        self.analysis_results = []
        self.quality_protocol = quality_protocol
        self.quality_partials = []
        self.quality_results = []
        self.work_scopes = []
        self.work_observations = []
        self.hierarchy_bundle = None
        self.hierarchy_groups = []
        self.hierarchy_group_partials = []
        self.hierarchy_current_group = None
        self.hierarchy_current_candidate = None
        self._money_mode = settings.contract_semantic_money_mode
        self._hierarchy_analysis_critical = (isinstance(replay, _ReplayGateway)
            and bool(replay.owner.manifest.get("hierarchy")))

    def _observe_money(self, payload):
        """Server-owned diagnostics; never alter the provider candidate or evidence."""
        warnings = []
        if isinstance(payload, dict):
            texts = [("summary", None, payload.get("summary"))]
            # A provider tag must never hide ordinary (even rejected) hints.
            # Schema failure must not hide money in a v2/unknown envelope.
            hint_fields = ("hints", "newHints")
            for hint_field in hint_fields:
                hints = payload.get(hint_field)
                if not isinstance(hints, list) or len(hints) > 100:
                    continue
                for index, hint in enumerate(hints):
                    if not isinstance(hint, dict):
                        continue
                    title, description = hint.get("title"), hint.get("description")
                    if isinstance(title, str) and isinstance(description, str):
                        raw_category = hint.get("category")
                        category = raw_category if isinstance(raw_category, str) and raw_category in _CONTRACT_CATEGORIES else None
                        texts.append((f"{hint_field}[{index}].description", category, title + "\n" + description))
            for path, category, text in texts:
                if not isinstance(text, str):
                    continue
                reason = self._monetary_rejection_reason(text)
                if reason in {"MONETARY_VALUE", "MONETARY_CALCULATION"}:
                    try:
                        text_hash = hashlib.sha256(text.encode("utf-8")).hexdigest()
                    except UnicodeEncodeError:
                        # No fake UTF-8/replacement hash. The original typed
                        # parser and encoding failure receipt retain authority.
                        continue
                    subtypes = [name for name, hit in (
                        ("ABSOLUTE_VALUE", _MONETARY_VALUE.search(text)),
                        ("LABELLED_NUMBER", _LABELLED_ABSOLUTE_NUMBER.search(text)),
                        ("ARITHMETIC", _has_monetary_calculation(text)),
                        ("RATE_COMPARISON", _MONETARY_RATE_COMPARISON.search(text)),
                        ("UNSAFE_CONCLUSION", _has_unsafe_monetary_conclusion(text))) if hit]
                    warnings.append({"fieldPath": path, "errorKind": reason, "category": category,
                                     "ruleSubtypes": subtypes,
                                     "textSha256": text_hash})
        return {"protocol": MONEY_OBSERVATION_PROTOCOL, "mode": self._money_mode, "warnings": warnings}

    def _call_contract(self, operation, budget):
        return super()._call_contract(replace(operation, generation_policy=POLICY_VERSION,
                                             quality_protocol=self.quality_protocol), budget)

    def _independent_money_repair_issues(self, payload, primary):
        """Supplement, never replace, a known typed-work primary failure.

        This bounded feedback scan proves neither evidence support nor the
        completeness of diagnostics. The unchanged full validator still owns
        acceptance after repair. No candidate field is changed or published.
        """
        issues = list(primary)
        if getattr(self, "_money_mode", "STRICT") == "OBSERVE":
            return tuple(issues)
        hints = payload.get("hints") if isinstance(payload, dict) else None
        if not isinstance(hints, list) or len(hints) > 100:
            return tuple(issues)
        seen = {(issue.field_path, issue.error_kind) for issue in issues}
        for index, raw in enumerate(hints):
            if len(issues) >= MAX_REPAIR_DIAGNOSTICS:
                break
            if not isinstance(raw, dict):
                continue
            try:
                hint = ProviderHintDraft.model_validate({key: value for key, value in raw.items()
                    if key not in {"claimKind", "obligationScope"}})
            except ValidationError:
                continue
            title, description = _provider_text(hint.title, 200), _provider_text(hint.description, 2000)
            if title is None or description is None:
                continue
            reason = self._monetary_rejection_reason(title + "\n" + description)
            path = f"hints[{index}].description"
            if reason not in {"MONETARY_VALUE", "MONETARY_CALCULATION"} or (path, reason) in seen:
                continue
            issues.append(ProviderValidationIssue(path, index, reason, "INVALID_SCOPED_CLAIM", (), True,
                category=hint.category, schema_version=QUALITY_PROTOCOL))
            seen.add((path, reason))
        return tuple(issues)

    def _normalize_contract_result(self, *args, **kwargs):
        # The authoritative draft parser rejects >100 hints instead of the
        # legacy normalizer's bounded slice silently losing a tail concern.
        kwargs["strict_machine_contract"] = True
        kwargs["monetary_mode"] = self._money_mode
        self.replay.monetary_observation = self._observe_money(args[0])
        raw_observation = self.replay.monetary_observation
        provider_refs = None
        business_paths=None
        def wire_issues(issues):
            if business_paths is not None:
                mapped=[]
                for issue in issues:
                    match=re.fullmatch(r'hints\[([0-9]+)\](.*)',issue.field_path)
                    if match and int(match[1])<len(business_paths):
                        issue=replace(issue,field_path=business_paths[int(match[1])]+match[2],item_index=None)
                    elif issue.field_path.startswith('resolutions'):
                        issue=replace(issue,field_path='decisions',item_index=None)
                    mapped.append(issue)
                return tuple(mapped)
            if provider_refs is None:
                return issues
            mapped = []
            for issue in issues:
                match = re.fullmatch(r"hints\[([0-9]+)\](.*)", issue.field_path)
                if match and int(match[1]) < len(provider_refs):
                    ref = provider_refs[int(match[1])]
                    if ref.startswith("N"):
                        issue = replace(issue, field_path="newHints[" + ref[1:] + "]" + match[2], item_index=None)
                    else:
                        issue = replace(issue, field_path="inheritedHints", item_index=None,
                            actual_class="SOURCE_" + ref.replace(":", "_") + "_" + issue.actual_class)
                mapped.append(issue)
            if 1 < len(mapped) <= MAX_REPAIR_DIAGNOSTICS and all(
                    issue.error_kind == "UNRESOLVED_LOCAL_OBSERVATION"
                    and issue.field_path == "inheritedHints" and issue.item_index is None
                    and re.fullmatch(r"SOURCE_G[1-3]_H(?:[1-9][0-9]?|100)_INVALID_SCOPED_CLAIM", issue.actual_class)
                    for issue in mapped):
                refs = [issue.actual_class.removeprefix("SOURCE_").removesuffix("_INVALID_SCOPED_CLAIM")
                        for issue in mapped]
                if len(set(refs)) == len(refs):
                    compact = "SOURCES_" + "__".join(refs) + "_INVALID_SCOPED_CLAIM"
                    if len(compact) <= 128:
                        return (replace(mapped[0], actual_class=compact),)
            return tuple(mapped)
        try:
            quality = None
            if self.quality_protocol:
                role = getattr(self.replay.operation, "hierarchy_role", None)
                synthesis = self.replay.operation.purpose == "SYNTHESIZE" and role != "GROUP"
                raw = args[0]
                business_adapter=False
                business_metadata=None
                if role in {"GROUP", "FINAL"}:
                    from audit_ai.model.semantic_hierarchy_provider import decode_provider, ProviderReferenceFailure, ProviderInheritedVisibilityFailure, ProviderFinalProtocolFailure
                    originals = ({lid: self.hierarchy_bundle["leaves"][lid]["hint"]
                        for lid in self.hierarchy_current_group["leafIds"]} if role == "GROUP" else
                        {f"G{gi+1}:H{hi+1}": hint for gi, group in enumerate(self.hierarchy_groups)
                         for hi, hint in enumerate(group["hints"])})
                    # This null is explicit in the frozen claimColumns wire.
                    # Public normalized hints omit nulls; do not mistake that
                    # serialization omission for a missing provider field.
                    wire_fields = ("category", "attention", "title", "description", "claimKind", "obligationScope",
                                   "problemEvidenceIds", "comparisonEvidenceIds")
                    originals = {ref: {**{key: hint[key] for key in wire_fields},
                                       "unavailableMaterialCategory": hint.get("unavailableMaterialCategory")}
                                 for ref, hint in originals.items()}
                    try:
                        from audit_ai.model.semantic_final_evidence import HIERARCHY_PROTOCOL, PROVIDER_PROTOCOL, fully_visible_evidence_ids
                        trusted_v2 = self.replay.operation.hierarchy_protocol == HIERARCHY_PROTOCOL
                        visible_options=({'fully_visible_evidence_ids':fully_visible_evidence_ids(
                            self.hierarchy_bundle['finalEvidenceProof'],args[3])} if trusted_v2 and role=='FINAL' else {})
                        if getattr(self.replay.owner,'business_adapter',False):
                            from audit_ai.model.semantic_business_adapter import decode,registry,PROTOCOL as business_protocol
                            business_source=json.loads(self.replay.operation.source_text)
                            if business_source.get('businessAdapterProtocol')!=business_protocol:raise _invalid()
                            expanded,business_paths=decode(raw,business_source,with_provenance=True)
                            business_adapter=True
                            business_metadata={'protocol':business_protocol,'issueIds':list(registry(business_source)),
                                'publicFindingOrigins':business_paths,
                                'inputSourceSha256':hashlib.sha256(self.replay.operation.source_text.encode()).hexdigest(),
                                'derivedRelationsSha256':checkpoint_hash(expanded.get('resolutions',expanded.get('lineage')))}
                        else:
                            expanded = decode_provider(raw, role=role, source_hints=originals,
                                evidence_ids=self.hierarchy_bundle["evidenceIds"],
                                **visible_options,
                                **({"protocol": PROVIDER_PROTOCOL} if trusted_v2 else {}))
                    except AuditAiError as error:
                        if isinstance(error, (ProviderReferenceFailure,ProviderInheritedVisibilityFailure,ProviderFinalProtocolFailure)) or (
                                getattr(self.replay.owner,'business_adapter',False) and hasattr(error,'field_path')):
                            issue = ProviderValidationIssue(error.field_path, None, error.code,
                                error.actual_class, (), False, schema_version=QUALITY_PROTOCOL)
                            raise QualityFailure(error.code, error.field_path, safe_issues=(issue,)) from None
                        raise QualityFailure(error.code, "lineage" if role == "GROUP" else "resolutions") from None
                    provider_refs = []
                    for row in ([] if business_adapter else raw["lineage" if role == "GROUP" else "resolutions"]):
                        for ref in ([row[2]] if role == "GROUP" else row[3]):
                            if ref not in provider_refs:
                                provider_refs.append(ref)
                    if role == "FINAL" and not business_adapter:
                        provider_refs.extend(f"N{i}" for i in range(len(raw["newHints"])) if f"N{i}" not in provider_refs)
                    raw = expanded
                    args = (raw, *args[1:])
                    expanded_observation = self._observe_money(raw)
                    observed, identities = [], set()
                    for warning in [*raw_observation["warnings"], *expanded_observation["warnings"]]:
                        warning = dict(warning)
                        path = re.fullmatch(r"hints\[([0-9]+)\]\.description", warning["fieldPath"])
                        if path and int(path[1]) < len(provider_refs):
                            ref = provider_refs[int(path[1])]
                            if ref.startswith("N"):
                                warning["fieldPath"] = "newHints[" + ref[1:] + "].description"
                        identity = (warning["fieldPath"], warning["category"], warning["errorKind"], warning["textSha256"])
                        if identity not in identities:
                            identities.add(identity); observed.append(warning)
                    self.replay.monetary_observation = {**expanded_observation, "warnings": observed}
                scoped = {key: value for key, value in raw.items() if key != "lineage"} if role == "GROUP" else raw
                from audit_ai.model.semantic_final_evidence import HIERARCHY_PROTOCOL
                process_enabled = role == "FINAL" and self.replay.operation.hierarchy_protocol == HIERARCHY_PROTOCOL
                if process_enabled:
                    for row in scoped.get("resolutions", []):
                        if row.get("disposition") in {"PROCESS_RESOLVED", "SCOPED_COUNTEREVIDENCE"} and self._hint_text_filter_reason(
                                row.get("reason", ""), monetary_mode=self._money_mode):
                            raise QualityFailure("PROCESS_REASON_UNSAFE", "resolutions")
                payload, quality = parse_scoped_draft(scoped, synthesis=synthesis, allow_process_resolution=process_enabled,
                    server_derived_basis=business_adapter and process_enabled)
                if business_metadata is not None:quality['businessAdapter']=business_metadata
                quality_payload = {**payload, "hints": [{**hint, **metadata}
                    for hint, metadata in zip(payload["hints"], quality["hintMetadata"], strict=True)]}
                validation_catalog = args[3]
                if process_enabled:
                    from audit_ai.model.semantic_final_evidence import visible_catalog
                    validation_catalog = visible_catalog(self.hierarchy_bundle['finalEvidenceProof'], args[3])
                if role in {"GROUP", "FINAL"}:
                    from audit_ai.model.semantic_hierarchy import check_critical_anchors
                    decision = ({"lineage": raw["lineage"]} if role == "GROUP" and isinstance(raw.get("lineage"), list)
                                else {"resolutions": quality["resolutions"]} if role == "FINAL" else {})
                    check_critical_anchors({**quality_payload, **decision}, catalog=validation_catalog)
                elif (self.replay.operation.purpose == "ANALYZE"
                      and self._hierarchy_analysis_critical):
                    # A literal inherited by GROUP must already satisfy the
                    # same cited-source anchor guard when ANALYZE freezes it.
                    # A summary has no per-hint citation contract here: do not
                    # treat an empty hint list as an empty reviewed source.
                    from audit_ai.model.semantic_hierarchy import check_critical_anchors
                    check_critical_anchors({"hints": quality_payload["hints"]}, catalog=args[3])
                check_claims(quality_payload, catalog=validation_catalog, available_categories=args[2],
                             allowed_evidence_ids=args[4], synthesis=synthesis,
                             **({'required_global_source_files': set(args[3].source_lengths)} if process_enabled else {}))
                if synthesis:
                    process_options = ({"allow_process_resolution": True,
                        "server_derived_basis": business_adapter,
                        "final_evidence_proof": self.hierarchy_bundle["finalEvidenceProof"],
                        "scope_refinement_proofs": [],
                        "process_scope_hashes": self.hierarchy_group_scope_hashes} if process_enabled else {})
                    quality["resolutions"] = reconcile_observations(self.quality_partials,
                        {**quality_payload, "resolutions": quality["resolutions"]}, allowed_evidence_ids=args[4], catalog=args[3], **process_options)
                    if process_enabled: quality['groupScopeRefinements']=process_options['scope_refinement_proofs']
                    if role == "FINAL":
                        from audit_ai.model.semantic_hierarchy import compose_final_resolutions
                        try:
                            composed, chain = compose_final_resolutions(self.hierarchy_bundle, self.hierarchy_groups,
                                {**quality_payload, "resolutions": quality["resolutions"]})
                        except AuditAiError as error:
                            raise QualityFailure(error.code, "resolutions") from None
                        quality["groupResolutions"] = quality["resolutions"]
                        if process_enabled:
                            process_options["process_scope_hashes"] = {s["scopeHash"] for s in self.work_scopes}
                            process_options['scope_refinement_proofs']=[]
                        quality["resolutions"] = reconcile_observations(self.hierarchy_original_partials,
                            {**quality_payload, "resolutions": composed}, allowed_evidence_ids=args[4], catalog=args[3], **process_options)
                        if process_enabled: quality["finalEvidenceProof"] = self.hierarchy_bundle["finalEvidenceProof"]
                        if process_enabled: quality['scopeRefinements']=process_options['scope_refinement_proofs']
                        quality["hierarchyLineage"] = chain
                        quality["originalLeafRegistry"] = self.hierarchy_bundle["originalLeafRegistry"]
                        quality["originalObligationScopes"] = self.hierarchy_bundle["scopes"]
                        quality["groupingFingerprint"] = self.hierarchy_bundle["groupingFingerprint"]
                else:
                    quality["checkedEvidenceIds"] = [segment.evidence_id for segment in args[3].segments
                                                     if segment.evidence_id in args[4]]
                    if role == "GROUP":
                        from audit_ai.model.semantic_hierarchy import validate_group_lineage, group_digest
                        candidate = {**quality_payload, "scopeStatus": quality["scopeStatus"], "lineage": raw.get("lineage")}
                        try:
                            self.hierarchy_current_candidate = validate_group_lineage(candidate,
                                self.hierarchy_current_group, self.hierarchy_bundle)
                            quality["groupDigest"] = group_digest(self.hierarchy_current_candidate,
                                self.hierarchy_current_group, self.hierarchy_bundle)
                        except AuditAiError as error:
                            rows = error.data.get("lineageIdentityIssues", []) if type(error.data) is dict else []
                            rows = rows if type(rows) is list else []
                            details = [row for row in rows[:8] if type(row) is dict and set(row) == {"fieldPath", "errorKind"}
                                and type(row["fieldPath"]) is str
                                and re.fullmatch(r"lineage\[(0|[1-9][0-9]*)\]", row["fieldPath"])
                                and row["errorKind"] in {"HIERARCHY_MERGE_IDENTITY_MISMATCH", "HIERARCHY_LEAF_IDENTITY_MISMATCH"}]
                            # One homogeneous error, all affected coordinates. Repeating
                            # identical issue metadata eight times wastes the fixed
                            # complete-candidate capacity; no source value is emitted.
                            details = (ProviderValidationIssue("lineage", None, "HIERARCHY_LEAF_IDENTITY_MISMATCH",
                                "ROWS_" + "_".join(re.fullmatch(r"lineage\[([0-9]+)\]", row["fieldPath"])[1]
                                    for row in details), (), True, schema_version=QUALITY_PROTOCOL),) if details else ()
                            raise QualityFailure(error.code, "lineage", safe_issues=details,
                                audit_reason=error.code if error.code == "SEMANTIC_HIERARCHY_DIGEST_BOUND"
                                else "INVALID_TYPED_SCHEMA") from None
                        quality["groupLineage"] = self.hierarchy_current_candidate["lineage"]
                        quality["groupingFingerprint"] = self.hierarchy_bundle["groupingFingerprint"]
                args = (payload, *args[1:])
            if self.quality_protocol and not synthesis:
                result = self._normalize_work_observations(args, kwargs, quality)
            else:
                result = super()._normalize_contract_result(*args, **kwargs)
            if self.quality_protocol and (result[1] or result[2]):
                rejected = next(((ordinal, item) for ordinal, item in result[3]
                                 if item.filter_reason is not None or item.evidence_incomplete), None)
                if rejected:
                    ordinal, item = rejected
                    raise QualityFailure(item.filter_reason.value if item.filter_reason else "EVIDENCE_SUPPORT_INVALID",
                        f"hints[{ordinal - 1}].description" if item.filter_reason else f"hints[{ordinal - 1}].problemEvidenceIds",
                        ordinal - 1, item.category)
                raise QualityFailure("EVIDENCE_OR_SAFETY_INVALID", "hints")
            self.replay.quality = quality
        except QualityFailure as failure:
            issue = ProviderValidationIssue(failure.field_path, failure.index,
                failure.code, "INVALID_SCOPED_CLAIM", (), True,
                category=failure.category, schema_version=QUALITY_PROTOCOL)
            issues = failure.safe_issues or (issue,)
            if failure.code in {"WORK_OBSERVATION_TYPE", "MATERIAL_ABSENCE_TYPE"}:
                issues = self._independent_money_repair_issues(args[0], issues)
            issues = wire_issues(issues)
            self.replay.validation_issues = [entry.as_dict() for entry in issues]
            # Existing audit/error taxonomy stays stable; the exact finite
            # quality coordinate is retained in validationIssues/errorKind.
            raise UnprocessableContractResult(failure.audit_reason, safe_issues=issues,
                rejected_operation_fingerprint=self._rejected_operation_fingerprint()) from None
        except UnprocessableContractResult as failure:
            failure.safe_issues = wire_issues(failure.safe_issues)
            self.replay.validation_issues = [issue.as_dict() for issue in failure.safe_issues]
            failure.rejected_operation_fingerprint = self._rejected_operation_fingerprint()
            raise
        self.replay.normalized = result[0].model_dump(by_alias=True, exclude_none=True)
        return result

    def _rejected_operation_fingerprint(self):
        packet = getattr(self.replay, "current", None) or getattr(self.replay, "fresh", None)
        return packet.get("operationFingerprint") if isinstance(packet, dict) else None

    def _normalize_work_observations(self, args, kwargs, quality):
        payload, _, _, catalog, allowed = args
        scope = _work_scope(catalog, allowed, quality["scopeStatus"])
        local_indexes = {i for i, row in enumerate(quality["hintMetadata"]) if row["claimKind"] == "LOCAL_NOT_SEEN"}
        # Everything except the explicit private type retains the original final authority.
        public_indexes = [i for i in range(len(payload["hints"])) if i not in local_indexes]
        ordinary = super()._normalize_contract_result(
            {**payload, "hints": [payload["hints"][i] for i in public_indexes]}, *args[1:], **kwargs)
        for ordinal, item in ordinary[3]:
            if item.filter_reason is not None or item.evidence_incomplete:
                index = public_indexes[ordinal - 1]
                raise QualityFailure(item.filter_reason.value if item.filter_reason else "EVIDENCE_SUPPORT_INVALID",
                    f"hints[{index}].description" if item.filter_reason else f"hints[{index}].problemEvidenceIds",
                    index, item.category)
        hints = dict(zip(public_indexes, ordinary[0].hints, strict=True))
        proofs = []
        for index in sorted(local_indexes):
            raw = payload["hints"][index]
            if raw["attention"] != "INSUFFICIENT" or raw["unavailableMaterialCategory"] is not None:
                raise QualityFailure("WORK_OBSERVATION_TYPE", f"hints[{index}].claimKind", index, raw["category"])
            title, description = _provider_text(raw["title"], 200), _provider_text(raw["description"], 2000)
            if title is None or description is None:
                raise QualityFailure("INVALID_TITLE_OR_DESCRIPTION", f"hints[{index}].description", index, raw["category"])
            reason = self._hint_text_filter_reason(title + "\n" + description, monetary_mode=self._money_mode)
            if reason:
                raise QualityFailure(reason.value, f"hints[{index}].description", index, raw["category"])
            ids = list(dict.fromkeys([*raw["problemEvidenceIds"], *raw["comparisonEvidenceIds"]]))
            if not ids or any(eid not in allowed or eid not in catalog.by_id or not catalog.by_id[eid].citable for eid in ids):
                raise QualityFailure("WORK_SCOPE_WITNESS_INVALID", f"hints[{index}].comparisonEvidenceIds", index, raw["category"])
            hints[index] = WorkScopeObservation(category=raw["category"], attention=raw["attention"],
                title=title, description=description, evidenceRefs=[],
                problem_evidence_ids=tuple(raw["problemEvidenceIds"]),
                comparison_evidence_ids=tuple(raw["comparisonEvidenceIds"]), work_scope_hash=scope["scopeHash"])
            proofs.append({"hintIndex": index, "type": "SCOPE_WITNESS_NOT_FINAL_EVIDENCE",
                           "scopeHash": scope["scopeHash"], "witnessEvidenceIds": ids})
        quality.update(workScope=scope, workObservations=proofs)
        if not local_indexes:
            return ordinary
        summary = _provider_text(payload["summary"], 4000) or "已完成合同文字语义分析。"
        if self._hint_text_filter_reason(summary, monetary_mode=self._money_mode):
            summary = _SAFE_FILTERED_SUMMARY
        ordered = assign_server_hint_ids([hints[i] for i in range(len(payload["hints"]))], operation_key=kwargs["operation_key"])
        diagnostics = tuple((i + 1, _ContractHintNormalization(hint, False, None, hint.category, hint.attention))
                            for i, hint in enumerate(ordered))
        return ContractChunkResult(summary=summary, hints=ordered), 0, (), diagnostics

    def _record_completion(self, calls, operation, completion, **kwargs):
        super()._record_completion(calls, operation, completion, **kwargs)
        self.replay.record(calls[-1])
        if self.quality_protocol and operation.purpose == "ANALYZE" and kwargs["outcome"] == "SUCCEEDED":
            self.quality_results.append(self.replay.quality["hintMetadata"])
            self.work_scopes.append(self.replay.quality["workScope"])
            self.work_observations.append(self.replay.quality["workObservations"])

    def _record_failure(self, calls, operation, reason, duration_ms):
        super()._record_failure(calls, operation, reason, duration_ms)
        self.replay.record(calls[-1])
        # v3 permits bounded protocol corrections, never automatic HTTP retries.
        raise _ProviderStopped()

    def _apply_contract_correction(self, **kwargs):
        if self.quality_protocol:
            # The complete original scope is regenerated under the same parser,
            # never patching an unsupported immutable title with a fake anchor.
            feedback = (ProviderValidationIssue("hints", None,
                kwargs["retry_reason"], "REQUIRED_CATEGORY_OR_EVIDENCE", tuple(kwargs["plan"].missing_required_categories)),)
            packet = self.replay.current if self.replay.current is not None else self.replay.fresh
            context = build_regeneration_context(packet["completion"]["payload"], feedback, self._rejected_operation_fingerprint())
            operation = replace(self.replay.operation, attempt=2, max_attempts=2,
                retry_reason=kwargs["retry_reason"], transport_attempt=1, max_transport_attempts=1,
                validation_feedback=feedback, regeneration_context_json=context)
            try:
                completion = self._call_contract(operation, kwargs["provider_budget"])
                result, filtered, repairs, _ = self._normalize_contract_result(completion.payload,
                    {}, kwargs["available_material_categories"], kwargs["evidence_catalog"], kwargs["allowed_evidence_ids"],
                    operation_key=kwargs["operation_key"], request_id=kwargs["request_id"])
                if filtered or repairs or not set(operation.required_categories).issubset({h.category for h in result.hints}):
                    raise UnprocessableContractResult("MISSING_REQUIRED_CATEGORY")
            except ModelGatewayFailure as failure:
                self._record_failure(kwargs["model_calls"], operation, failure.reason, failure.duration_ms)
                raise
            except UnprocessableContractResult as failure:
                self._record_completion(kwargs["model_calls"], operation, completion,
                    outcome="FAILED", safe_reason=failure.safe_reason)
                raise self._invalid_result(kwargs["model_calls"]) from None
            self._record_completion(kwargs["model_calls"], operation, completion, outcome="SUCCEEDED")
            outcome = _CorrectionOutcome(result, completion.model, completion.duration_ms, 1, 0, False)
        else:
            # Direct text-analysis provenance is not part of the frozen work
            # request. Preserve this channel's own policy and recorded bytes.
            outcome = super()._apply_contract_correction(**{**kwargs, "document_redaction_provenance": False})
        corrected = outcome.result.model_dump(by_alias=True, exclude_none=True)
        if self.replay.current is not None:
            if self.replay.current.get("correctedResult") != corrected:
                raise _invalid()
        elif self.replay.fresh is not None:
            self.replay.fresh["correctedResult"] = corrected
        return outcome

    def _contract_synthesis_source(self, results, evidence_catalog=None):
        # No prefix truncation or successful 'skip synthesis' fallback in v2.
        self.analysis_results = list(results)
        identities = self._contract_synthesis_evidence_catalog(results, evidence_catalog, exact_text_chars=None)
        referenced = {evidence_id for result in results for hint in result.hints
                      for evidence_id in (*hint.problem_evidence_ids, *hint.comparison_evidence_ids)}
        if referenced != {identity["evidenceId"] for identity in identities}:
            # A malformed/uncitable identity must not disappear during factoring.
            raise _invalid()
        for identity in identities:
            segment = evidence_catalog.by_id[identity["evidenceId"]]
            identity.pop("exactText")
            identity.update(sourceStart=segment.source_start, sourceEnd=segment.source_end,
                            exactTextSha256=hashlib.sha256(segment.exact_text.encode("utf-8")).hexdigest())
        value = {
            "partialResults": [{"summary": result.summary, "hints": [
                self._contract_synthesis_hint(hint, title_chars=None, description_chars=None,
                                              excerpt_chars=None) for hint in result.hints]}
                               for result in results],
            "evidenceCatalog": identities,
            "omittedHintCount": 0,
        }
        if self.quality_protocol:
            from audit_ai.model.semantic_quality import ClaimKind
            from typing import get_args
            kinds, scopes = list(get_args(ClaimKind)), []
            self.quality_partials = []
            if len(self.quality_results) != len(results):
                raise _invalid()
            if len(self.work_scopes) != len(results) or len(self.work_observations) != len(results):
                raise _invalid()
            for p, (partial, metadata) in enumerate(zip(value["partialResults"], self.quality_results, strict=True)):
                if len(partial["hints"]) != len(metadata):
                    raise _invalid()
                expanded = []
                witnesses = {entry["hintIndex"]: entry for entry in self.work_observations[p]}
                for h, (hint, quality) in enumerate(zip(partial["hints"], metadata, strict=True)):
                    scope = quality["obligationScope"]
                    if scope not in scopes: scopes.append(scope)
                    expanded.append({**hint, **quality})
                    if h in witnesses:
                        if not isinstance(results[p].hints[h], WorkScopeObservation):
                            raise _invalid()
                        expanded[-1]["workScopeWitness"] = {"protocol": WORK_SCOPE_PROTOCOL, **witnesses[h]}
                    hint["q"] = [kinds.index(quality["claimKind"]), scopes.index(scope)]
                self.quality_partials.append({"summary": partial["summary"], "hints": expanded})
            value["qualityRegistry"] = {"claimKinds": kinds, "obligationScopes": scopes}
            value["workScopeRegistry"] = _synthesis_work_scopes(self.work_scopes)
        else:
            self.quality_partials = value["partialResults"]
        source = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        if len(source) > 12_000:
            value = _compact_synthesis_evidence(value)
            source = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        if len(source) > 12_000 and self.quality_protocol:
            value = _compact_quality_hints(value)
            source = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
        if len(source) > 12_000 and self.quality_protocol:
            from audit_ai.model.semantic_synthesis_packing import pack_synthesis_metadata
            source = json.dumps(pack_synthesis_metadata(value), ensure_ascii=False, separators=(",", ":"))
        if len(source) > 12_000:
            raise AuditAiError(413, "SEMANTIC_SYNTHESIS_INPUT_BOUND", "complete synthesis exceeds configured bound")
        return source, len(source)

    def _bounded_hierarchy_phase(self, *, request, request_id, results, evidence_catalog,
            available_categories, required_categories, provider_budget, model_calls, semantic_retry_count):
        """One versioned private GROUP layer, using the existing replay gateway.

        No persistence or budget is created here. Replaying all verified A/G
        packets rederives membership, literal digests and full original lineage.
        Every actual call still crosses _ReplayGateway's sole fresh boundary.
        """
        from audit_ai.model.semantic_hierarchy import build_hierarchy_inputs, final_source
        from audit_ai.model.semantic_final_evidence import HIERARCHY_PROTOCOL, catalog_rows
        protocol = request.semantic_hierarchy_protocol
        from audit_ai.model.gateway import ContractTextOperation
        if request.semantic_money_mode != "OBSERVE" or self.quality_protocol != QUALITY_PROTOCOL:
            raise AuditAiError(409, "SEMANTIC_HIERARCHY_POLICY_INCOMPATIBLE", "hierarchy requires observation quality work")
        self.analysis_results = list(results)
        analyses = []
        packets = [*self.replay.prior, *([self.replay.fresh] if self.replay.fresh else [])]
        units = self.replay.owner.manifest["units"]
        analysis_units = [u for u in units if u["role"] == "ANALYSIS"]
        if len(results) != len(analysis_units) or len(self.quality_results) != len(results): raise _invalid()
        for i, (result, unit) in enumerate(zip(results, analysis_units, strict=True)):
            matching = [p for p in packets if p["logicalKey"] == unit["logicalKey"] and p["status"] == "VERIFIED"]
            if len(matching) != 1: raise _invalid()
            packet = matching[0]
            hints = []
            proofs = {entry["hintIndex"]: entry for entry in self.work_observations[i]}
            for hi, (hint, metadata) in enumerate(zip(result.hints, self.quality_results[i], strict=True)):
                value = {**self._contract_synthesis_hint(hint, title_chars=None, description_chars=None, excerpt_chars=None), **metadata}
                if hi in proofs: value["workScopeWitness"] = {"protocol": WORK_SCOPE_PROTOCOL, **proofs[hi]}
                hints.append(value)
            analyses.append({"logicalKey": unit["logicalKey"], "checkpointHash": checkpoint_hash({
                key: value for key, value in packet.items() if key != "checkpointHash"}), "summary": result.summary, "hints": hints})
        self.hierarchy_original_partials = [{"summary": a["summary"], "hints": a["hints"]} for a in analyses]
        catalog = [{"evidenceId": e.evidence_id, "fileId": e.file_id, "materialCategory": e.material_category,
            "sourceStart": e.source_start, "sourceEnd": e.source_end, "citable": e.citable} for e in evidence_catalog.segments]
        if protocol == HIERARCHY_PROTOCOL: catalog = catalog_rows(evidence_catalog)
        self.hierarchy_bundle = build_hierarchy_inputs(analyses, catalog,
            material_states=self.replay.owner.manifest["coveragePlan"]["materialStates"], protocol=protocol,
            frozen_catalog=evidence_catalog if protocol == HIERARCHY_PROTOCOL else None)
        self.hierarchy_groups, self.hierarchy_group_partials = [], []
        self.hierarchy_group_scope_hashes = set()
        stage_models, duration, count = [], 0, 0
        group_units = [u for u in units if u["role"] == "GROUP"]
        stages = [*zip(group_units, self.hierarchy_bundle["groups"], strict=True),
                  (units[-1], None)]
        for unit, group in stages:
            role = unit["role"]
            if role == "GROUP":
                source = group["source"]
                self.hierarchy_current_group = group
                allowed = {eid for ai in group["members"] for h in analyses[ai]["hints"]
                    for eid in (*h["problemEvidenceIds"], *h["comparisonEvidenceIds"])}
            else:
                source = final_source(self.hierarchy_bundle, [g["digest"] for g in self.hierarchy_group_partials])
                allowed = set(self.hierarchy_bundle.get("visibleEvidenceIds", self.hierarchy_bundle["evidenceIds"]))
                self.quality_partials = [{"summary": g["summary"], "hints": g["hints"]} for g in self.hierarchy_group_partials]
            if getattr(self.replay.owner,'business_adapter',False):
                from audit_ai.model.semantic_business_adapter import PROTOCOL as business_protocol
                source=_json({**json.loads(source),'businessAdapterProtocol':business_protocol})
            attempt, feedback, retry_reason, context = 1, (), None, None
            max_attempts = 2 if semantic_retry_count < 2 else 1
            while True:
                operation = ContractTextOperation(project_id=request.project_id, chunk_index=unit["ordinal"],
                    chunk_count=len(units), source_text=source, request_id=request_id, purpose="SYNTHESIZE",
                    attempt=attempt, max_attempts=max_attempts, retry_reason=retry_reason,
                    validation_feedback=feedback, regeneration_context_json=context,
                    generation_policy=POLICY_VERSION, quality_protocol=QUALITY_PROTOCOL,
                    hierarchy_protocol=protocol, hierarchy_role=role, hierarchy_logical_key=unit["logicalKey"],
                    grouping_fingerprint=self.hierarchy_bundle["groupingFingerprint"],
                    globally_available_material_categories=tuple(sorted(available_categories)),
                    current_operation_material_categories=tuple(sorted({evidence_catalog.by_id[eid].material_category for eid in allowed})),
                    required_categories=tuple(required_categories) if role == "FINAL" else (),
                    max_transport_attempts=1)
                try:
                    count += 1
                    completion = self._call_contract(operation, provider_budget)
                except ModelGatewayFailure as failure:
                    self._record_failure(model_calls, operation, failure.reason, failure.duration_ms)
                    raise
                duration += completion.duration_ms
                try:
                    normalized, filtered, repairs, _ = self._normalize_contract_result(completion.payload, {},
                        available_categories, evidence_catalog, allowed,
                        operation_key=f"G{group['ordinal']}" if group else "F1", request_id=request_id)
                    missing = set(operation.required_categories) - {h.category for h in normalized.hints}
                    if filtered or repairs or missing:
                        issue = ProviderValidationIssue("hints", None, "MISSING_REQUIRED_CATEGORY", "REQUIRED_CATEGORY_OR_EVIDENCE", tuple(sorted(missing)))
                        raise UnprocessableContractResult("MISSING_REQUIRED_CATEGORY", safe_issues=(issue,),
                            rejected_operation_fingerprint=self._rejected_operation_fingerprint())
                except UnprocessableContractResult as failure:
                    self._record_completion(model_calls, operation, completion, outcome="FAILED", safe_reason=failure.safe_reason)
                    if attempt >= max_attempts: raise self._invalid_result(model_calls) from None
                    feedback, retry_reason = failure.safe_issues, failure.safe_reason
                    context = build_regeneration_context(completion.payload, feedback, failure.rejected_operation_fingerprint,
                        hierarchy=True,business_adapter=getattr(self.replay.owner,'business_adapter',False))
                    semantic_retry_count += 1
                    attempt = 2
                    continue
                self._record_completion(model_calls, operation, completion, outcome="SUCCEEDED")
                stage_models.append(completion.model)
                if group is not None:
                    candidate = self.hierarchy_current_candidate
                    self.hierarchy_group_scope_hashes.add(self.replay.quality["workScope"]["scopeHash"])
                    self.hierarchy_groups.append(candidate)
                    partial = {"summary": candidate["summary"], "hints": [dict(h) for h in candidate["hints"]],
                        "digest": self.replay.quality["groupDigest"]}
                    for proof in self.replay.quality.get("workObservations", []):
                        partial["hints"][proof["hintIndex"]]["workScopeWitness"] = {"protocol": WORK_SCOPE_PROTOCOL, **proof}
                    self.hierarchy_group_partials.append(partial)
                break
        return [normalized], stage_models, duration, count, semantic_retry_count


class ContractSemanticWorkService:
    def __init__(self, settings, gateway):
        self.settings = settings
        self.gateway = gateway if isinstance(gateway, _DeadlineGateway) else _DeadlineGateway(gateway)
        adapter_mode=os.environ.get('AUDIT_CONTRACT_SEMANTIC_BUSINESS_ADAPTER','off')
        if adapter_mode not in {'off','v1'}:raise _invalid()
        self.business_adapter=adapter_mode=='v1'

    def capture_synthetic_candidate(self,replay,operation,completion):
        """Opt-in only, before provider normalization; no capture on replay."""
        config_path=os.environ.get('AUDIT_CONTRACT_SYNTHETIC_REPLAY_CONFIG')
        if not config_path or not self.business_adapter or operation.hierarchy_role not in {'GROUP','FINAL'}:return
        # This private wire does not yet carry independently proven Run/Parse
        # identities. Never label a provider response using configuration alone.
        # The separately attested, one-shot diagnostic has its own exact ticket.
        raise AuditAiError(409,'SYNTHETIC_REPLAY_EXECUTION_IDENTITY_UNPROVEN',
            'SYNTHETIC_REPLAY_EXECUTION_IDENTITY_UNPROVEN')

    def _manifest(self, request):
        hierarchy = request.semantic_hierarchy_protocol is not None
        if hierarchy and (request.semantic_money_mode != "OBSERVE" or request.semantic_quality_protocol != QUALITY_PROTOCOL):
            raise AuditAiError(409, "SEMANTIC_HIERARCHY_POLICY_INCOMPATIBLE", "hierarchy requires approved observation quality work")
        if request.semantic_money_mode == "OBSERVE" and (
                self.settings.contract_semantic_money_mode != "OBSERVE"
                or request.project_id not in self.settings.contract_semantic_observe_project_ids):
            raise AuditAiError(409, "SEMANTIC_MONEY_MODE_CONFLICT", "observation is not authorized for this frozen project")
        if self.settings.demo_relax_breach_monetary:
            raise AuditAiError(409, "SEMANTIC_POLICY_INCOMPATIBLE", "relaxed demo validation is not eligible for durable semantic review")
        if self.settings.semantic_structured_transport != "PROMPT_ONLY":
            raise AuditAiError(409, "SEMANTIC_POLICY_INCOMPATIBLE", "contract v3 requires the approved prompt-only transport")
        service = ModelApplicationService(self.settings, self.gateway, require_synthesis=True)
        catalog = service._contract_evidence_catalog(request)
        batches = service._contract_batches(catalog)
        chunks = tuple(batch.source_text for batch in batches)
        if hierarchy:
            from audit_ai.model.semantic_hierarchy import admit_analysis_count
            admission = admit_analysis_count(len(chunks))
        request_value = request.model_dump(by_alias=True)
        if not hierarchy: request_value.pop("semanticHierarchyProtocol", None)
        input_fingerprint = checkpoint_hash(request_value)
        # Freeze executable prompt/normalizer/correction code, not just a display version.
        code_files = (Path(__file__), Path(gateway_module.__file__),
                      *(Path(__file__).with_name(name) for name in (
                          "router.py", "contract_correction.py", "contract_draft.py", "evidence.py", "chunker.py")))
        policy = {"version": POLICY_VERSION, "model": self.settings.model_name,
                  "draftProtocol": "contract-provider-draft-v2", "typedRepair": "FULL_REGENERATION",
                  "reasoningEffort": "none", "maxOutputTokens": 6144, "automaticTransportRetries": 0,
                  "provider": ("OLLAMA_NATIVE" if self.settings.semantic_structured_transport == "OLLAMA_NATIVE_STRUCTURED" else "OPENAI_COMPATIBLE"),
                  "endpointFingerprint": checkpoint_hash(self.settings.model_base_url),
                  "transport": self.settings.semantic_structured_transport,
                  "demoRelaxation": False,
                  "monetaryMode": request.semantic_money_mode,
                  "monetaryObservationProtocol": MONEY_OBSERVATION_PROTOCOL,
                  "synthesisInputFormat": gateway_module.CONTRACT_SYNTHESIS_INPUT_FORMAT,
                  "synthesisPromptVersion": gateway_module.CONTRACT_SYNTHESIS_PROMPT_VERSION,
                  "materialScopeProtocol": gateway_module.CONTRACT_MATERIAL_SCOPE_PROTOCOL,
                  "materialScopeSchemaProtocol": gateway_module.CONTRACT_MATERIAL_SCOPE_SCHEMA_PROTOCOL,
                  "chunkChars": self.settings.model_chunk_chars, "maxChunks": self.settings.model_max_chunks,
                  "budgetSeconds": min(900.0, self.settings.model_total_budget_seconds),
                  "sourceCodeHashes": [hashlib.sha256(path.read_bytes()).hexdigest() for path in code_files]}
        policy_fingerprint = checkpoint_hash(policy)
        if request.semantic_quality_protocol:
            policy["qualityProtocol"] = request.semantic_quality_protocol
            policy["moneyRepairGuidanceProtocol"] = MONEY_REPAIR_GUIDANCE_PROTOCOL
            policy["repairDiagnosticsProtocol"] = REPAIR_DIAGNOSTICS_PROTOCOL
            policy["maximumRepairDiagnostics"] = MAX_REPAIR_DIAGNOSTICS
            policy["regenerationContextProtocol"] = REGENERATION_CONTEXT_PROTOCOL
            policy["regenerationContextMaxBytes"] = MAX_REGENERATION_CONTEXT_BYTES
            from audit_ai.model.semantic_regeneration_packing import PROTOCOL as regeneration_packing_protocol
            policy["regenerationCandidatePacking"] = regeneration_packing_protocol
            policy["sourceCodeHashes"].append(hashlib.sha256(Path(__file__).with_name("semantic_regeneration_packing.py").read_bytes()).hexdigest())
            policy["workScopeProtocol"] = WORK_SCOPE_PROTOCOL
            policy["workClaimTypeProtocol"] = WORK_CLAIM_TYPE_PROTOCOL
            policy["qualityHintEncoding"] = QUALITY_HINT_ENCODING
            from audit_ai.model.semantic_synthesis_packing import PROTOCOL as packing_protocol
            policy["synthesisMetadataPacking"] = packing_protocol
            quality_path = Path(__file__).with_name("semantic_quality.py")
            policy["sourceCodeHashes"].append(hashlib.sha256(quality_path.read_bytes()).hexdigest())
            policy["sourceCodeHashes"].append(hashlib.sha256(Path(__file__).with_name("semantic_synthesis_packing.py").read_bytes()).hexdigest())
            policy_fingerprint = checkpoint_hash(policy)
        if hierarchy:
            from audit_ai.model.semantic_hierarchy import PROTOCOL as hierarchy_protocol, MEMBERSHIP_STRATEGY
            from audit_ai.model.semantic_hierarchy_provider import PROTOCOL as provider_protocol
            policy.update(hierarchyProtocol=hierarchy_protocol, hierarchyMembershipStrategy=MEMBERSHIP_STRATEGY,
                hierarchyMaximumGroups=3, hierarchyMaximumAnalyses=16, hierarchyGroupInputMaxChars=12000,
                hierarchyFinalInputMaxChars=12000, hierarchyGroupSummaryMaxChars=240, hierarchyReasonMaxChars=160)
            policy["hierarchyProviderDraftProtocol"] = provider_protocol
            policy["hierarchyAnalysisCriticalFacts"] = "contract-cited-analysis-critical-facts-v1"
            from audit_ai.model.semantic_hierarchy import GROUP_DIGEST_REASON_ENCODING
            policy["hierarchyDigestReasonEncoding"] = GROUP_DIGEST_REASON_ENCODING
            from audit_ai.model.semantic_final_evidence import HIERARCHY_PROTOCOL, PROVIDER_PROTOCOL, PROTOCOL as view_protocol, MAX_CHARS, MAX_BYTES, TEXT_ENCODING
            if request.semantic_hierarchy_protocol == HIERARCHY_PROTOCOL:
                policy.update(hierarchyProtocol=HIERARCHY_PROTOCOL, hierarchyProviderDraftProtocol=PROVIDER_PROTOCOL,
                    hierarchyFinalInputMaxChars=MAX_CHARS, hierarchyFinalInputMaxBytes=MAX_BYTES,
                    finalEvidenceProtocol=view_protocol, finalEvidenceEncoding=TEXT_ENCODING)
                policy["sourceCodeHashes"].append(hashlib.sha256(Path(__file__).with_name("semantic_final_evidence.py").read_bytes()).hexdigest())
                policy["sourceCodeHashes"].append(hashlib.sha256(Path(__file__).with_name("semantic_scope_refinement.py").read_bytes()).hexdigest())
                if self.business_adapter:
                    from audit_ai.model.semantic_business_adapter import PROTOCOL as business_protocol,MAX_DERIVED_IDS,MAX_DERIVED_BYTES
                    policy.update(businessAdapterProtocol=business_protocol,hierarchyProviderDraftProtocol=business_protocol,serverDerivedMaxIds=MAX_DERIVED_IDS,
                        serverDerivedMaxBytes=MAX_DERIVED_BYTES)
                    from audit_ai.model.semantic_quality import BUSINESS_REGENERATION_CONTEXT_PROTOCOL,MAX_BUSINESS_REGENERATION_CONTEXT_BYTES
                    policy.update(businessRegenerationProtocol=BUSINESS_REGENERATION_CONTEXT_PROTOCOL,
                        businessRegenerationMaxBytes=MAX_BUSINESS_REGENERATION_CONTEXT_BYTES)
                    policy['sourceCodeHashes'].append(hashlib.sha256(Path(__file__).with_name('semantic_business_adapter.py').read_bytes()).hexdigest())
                    from audit_ai.model.semantic_token_budget import PROTOCOL as context_protocol
                    policy.update(businessContextBudgetProtocol=context_protocol,
                        businessTokenizerMetadataSha256=os.environ.get('AUDIT_CONTRACT_BUSINESS_TOKENIZER_SHA256'))
                    policy['sourceCodeHashes'].append(hashlib.sha256(Path(__file__).with_name('semantic_token_budget.py').read_bytes()).hexdigest())
            elif self.business_adapter:raise _invalid()
            policy["sourceCodeHashes"].append(hashlib.sha256(Path(__file__).with_name("semantic_hierarchy.py").read_bytes()).hexdigest())
            policy["sourceCodeHashes"].append(hashlib.sha256(Path(__file__).with_name("semantic_hierarchy_provider.py").read_bytes()).hexdigest())
            policy_fingerprint = checkpoint_hash(policy)
        seed = {"input": input_fingerprint, "policy": policy_fingerprint}
        units = []
        for index, chunk in enumerate(chunks, 1):
            units.append({"logicalKey": checkpoint_hash({**seed, "stage": f"A{index}"}),
                          "ordinal": index, "purpose": "ANALYZE",
                          "inputFingerprint": checkpoint_hash(chunk),
                          "evidenceIds": list(batches[index - 1].evidence_ids),
                          "dependencies": [units[-1]["logicalKey"]] if units else []})
        if hierarchy:
            for unit in units: unit["role"] = "ANALYSIS"
            analysis_units = list(units)
            for gi in range(admission["groupCount"]):
                units.append({"logicalKey": checkpoint_hash({**seed, "stage": f"G{gi + 1}"}),
                    "ordinal": len(units) + 1, "purpose": "SYNTHESIZE", "role": "GROUP",
                    "inputFingerprint": checkpoint_hash({"stage": f"G{gi + 1}", "analyses": [a["inputFingerprint"] for a in analysis_units]}),
                    "evidenceIds": [], "dependencies": [a["logicalKey"] for a in analysis_units]})
            units.append({"logicalKey": checkpoint_hash({**seed, "stage": "F1"}),
                "ordinal": len(units) + 1, "purpose": "SYNTHESIZE", "role": "FINAL",
                "inputFingerprint": checkpoint_hash([unit["inputFingerprint"] for unit in units]),
                "evidenceIds": [], "dependencies": [unit["logicalKey"] for unit in units]})
        else:
            units.append({"logicalKey": checkpoint_hash({**seed, "stage": "S1"}),
                      "ordinal": len(chunks) + 1, "purpose": "SYNTHESIZE",
                      "inputFingerprint": checkpoint_hash([unit["inputFingerprint"] for unit in units]),
                      "evidenceIds": [], "dependencies": [unit["logicalKey"] for unit in units]})
        manifest = {"version": 2, "inputFingerprint": input_fingerprint, "policyFingerprint": policy_fingerprint,
                    "policy": policy, "units": units, "repairLimit": 2,
                    "callLimit": len(units) + 2,
                    "maxOutputTokensPerCall": 6144, "maxTotalOutputTokens": (len(units) + 2) * 6144,
                    "unitTimeLimitMs": 300_000, "synthesisMaxChars": 12_000,
                    "timeLimitMs": round(min(900.0, self.settings.model_total_budget_seconds) * 1000),
                    "evidence": [{"evidenceId": item.evidence_id, "fileId": item.file_id,
                                  "category": item.material_category, "start": item.source_start,
                                  "end": item.source_end, "citable": item.citable,
                                  "textSha256": hashlib.sha256(item.exact_text.encode("utf-8")).hexdigest()}
                                 for item in catalog.segments]}
        if hierarchy:
            manifest["hierarchy"] = {"version": 1,
                "groupLogicalKeys": [u["logicalKey"] for u in units if u["role"] == "GROUP"],
                "finalLogicalKey": units[-1]["logicalKey"], "membershipStrategy": "scope-file-size-v1",
                "maximumGroups": 3, "leafIdentity": "analysis-logical-key+hint-ordinal",
                "decisionAfter": "ALL_ANALYSES_VERIFIED", "groupInputMaxChars": 12000, "finalInputMaxChars": 12000}
            if request.semantic_hierarchy_protocol == HIERARCHY_PROTOCOL:
                manifest["hierarchy"].update(version=2, finalInputMaxChars=MAX_CHARS,
                    finalInputMaxBytes=MAX_BYTES, finalEvidenceProtocol=view_protocol)
        if request.semantic_quality_protocol:
            global_categories = {material.category for material in request.materials}
            global_checks = _stable_required_categories(
                _CONTRACT_BODY_REQUIRED_CATEGORIES if "CONTRACT_BODY" in global_categories else (),
                ("BREACH_LIABILITY",) if any(_requires_quantified_breach_hint(chunk) for chunk in chunks) else ())
            manifest["coveragePlan"] = {
                "version": "contract-obligation-coverage-v1",
                "globalChecks": [{"category": category, "responsibleUnitKeys": [units[-1]["logicalKey"]],
                                  "completion": "ALL_LOCAL_SCOPES_VERIFIED_AND_SYNTHESIS_VALIDATED"}
                                 for category in global_checks],
                "localScopes": [{"logicalKey": unit["logicalKey"], "checkedEvidenceIds": unit["evidenceIds"],
                                 "requiredCategories": list(_stable_required_categories(
                                     _CONTRACT_BODY_REQUIRED_CATEGORIES if len(chunks) == 1 and "CONTRACT_BODY" in global_categories else (),
                                     ("BREACH_LIABILITY",) if _requires_quantified_breach_hint(chunks[index]) else ())) }
                                for index, unit in enumerate(units[:len(chunks)])],
                "materialStates": [{"fileId": material.file_id, "category": material.category,
                                    "state": "PROVIDED_PARSED_IN_FROZEN_SCOPE"} for material in request.materials],
            }
        if hierarchy:
            from audit_ai.model.semantic_hierarchy import validate_hierarchy_manifest
            validate_hierarchy_manifest(manifest)
        manifest["planFingerprint"] = checkpoint_hash(manifest)
        return manifest

    def _descriptor(self, operation):
        manifest = self.manifest
        is_synthesis = operation.purpose == "SYNTHESIZE" or (
            operation.purpose != "ANALYZE" and getattr(self, "synthesis_started", False))
        if operation.purpose == "SYNTHESIZE":
            self.synthesis_started = True
        if manifest.get("hierarchy") and is_synthesis and not operation.hierarchy_logical_key:
            raise _invalid()
        if manifest.get("hierarchy") and operation.hierarchy_logical_key:
            unit = next((u for u in manifest["units"] if u["logicalKey"] == operation.hierarchy_logical_key), None)
            if unit is None or unit["role"] != operation.hierarchy_role: raise _invalid()
        else:
            unit = manifest["units"][-1] if is_synthesis else manifest["units"][operation.chunk_index - 1]
        op = asdict(operation)
        op.pop("request_id")
        op.pop("provider_timeout_seconds")
        if not manifest.get("hierarchy"):
            for key in ("hierarchy_protocol", "hierarchy_role", "hierarchy_logical_key", "grouping_fingerprint"): op.pop(key)
        descriptor = {"logicalKey": unit["logicalKey"], "ordinal": unit["ordinal"],
                "purpose": ("REPAIR" if operation.attempt > 1 or operation.transport_attempt > 1
                            or operation.purpose not in {"ANALYZE", "SYNTHESIZE"} else operation.purpose),
                "operationFingerprint": checkpoint_hash({"plan": manifest["planFingerprint"], "operation": op}),
                "inputFingerprint": checkpoint_hash(operation.source_text)}
        if manifest.get("hierarchy"):
            descriptor["role"] = unit["role"]
            if operation.grouping_fingerprint: descriptor["groupingFingerprint"] = operation.grouping_fingerprint
        return descriptor

    def plan(self, request):
        manifest = self._manifest(request)
        described = self.advance(request, manifest["planFingerprint"], [], None,
                                 remaining_ms=manifest["timeLimitMs"], execute=False)
        return {**manifest, "next": described["next"]}

    def advance(self, request, plan_fingerprint, checkpoints, expected_operation_fingerprint,
                *, remaining_ms, execute, request_id="semantic-work"):
        started = time.monotonic()
        if not isinstance(remaining_ms, int) or isinstance(remaining_ms, bool) or remaining_ms <= 0:
            raise AuditAiError(504, "MODEL_TOTAL_BUDGET_EXHAUSTED", "semantic plan deadline has expired")
        # Per invocation only; no mutable shared cursor can leak across projects.
        invocation = ContractSemanticWorkService(self.settings, self.gateway)
        invocation.deadline = started + min(remaining_ms / 1000, self.settings.model_total_budget_seconds, 300.0)
        invocation.manifest = invocation._manifest(request)
        invocation.active_request=request
        if execute and os.environ.get('AUDIT_CONTRACT_SYNTHETIC_REPLAY_CONFIG'):
            raise AuditAiError(409,'SYNTHETIC_REPLAY_EXECUTION_IDENTITY_UNPROVEN',
                'SYNTHETIC_REPLAY_EXECUTION_IDENTITY_UNPROVEN')
        if invocation.manifest["planFingerprint"] != plan_fingerprint or len(checkpoints) > MAX_CHECKPOINTS:
            raise _invalid()
        for checkpoint in checkpoints:
            if not isinstance(checkpoint, dict) or checkpoint.get("status") not in {"VERIFIED", "REPAIR_REQUIRED"}:
                raise _invalid()
            payload = {key: value for key, value in checkpoint.items() if key != "checkpointHash"}
            if (len(_json(checkpoint).encode("utf-8")) > MAX_CHECKPOINT_BYTES
                    or checkpoint.get("checkpointHash") != checkpoint_hash(payload)):
                raise _invalid()
            if invocation.manifest.get("hierarchy"):
                unit = next((u for u in invocation.manifest["units"] if u["logicalKey"] == checkpoint.get("logicalKey")), None)
                if (unit is None or checkpoint.get("role") != unit["role"]
                        or ("finalResult" in checkpoint and (unit["role"] != "FINAL" or checkpoint["status"] != "VERIFIED"))):
                    raise _invalid()
        replay = _ReplayGateway(invocation, checkpoints, expected_operation_fingerprint, execute)
        settings = replace(self.settings, contract_semantic_money_mode=request.semantic_money_mode, model_total_budget_seconds=min(
            self.settings.model_total_budget_seconds, remaining_ms / 1000, 300.0))
        application = _JournaledApplication(settings, replay, request.semantic_quality_protocol)
        result = None
        next_operation = None
        failure_code = None
        try:
            # The legacy decorator interprets a deliberate single-unit boundary
            # as an application failure. This route owns its one-unit lifecycle.
            envelope = ModelApplicationService.analyze_contract.__wrapped__(application, request, request_id)
            result = envelope.data.model_dump(by_alias=True, exclude_none=True)
            # Synthesis is additive and may identify cross-material issues. It
            # cannot erase any previously validated finding or its evidence.
            from audit_ai.model.router import ContractChunkResult
            combined = (envelope.data.hints if request.semantic_quality_protocol else
                application._deduplicate_contract_hints([
                    *application.analysis_results,
                    ContractChunkResult(summary=envelope.data.summaries[0], hints=envelope.data.hints),
                ]))
            result["hints"] = [hint.model_dump(by_alias=True, exclude_none=True) for hint in combined]
            result["available"] = True
            packets = [*checkpoints, *([replay.fresh] if replay.fresh is not None else [])]
            work_warnings = []
            for packet in packets:
                observation = packet.get("monetaryObservation")
                if packet.get("status") == "VERIFIED" and observation and observation["warnings"]:
                    work_warnings.append({key: packet[key] for key in (
                        "logicalKey", "ordinal", "purpose", "operationFingerprint")})
                    work_warnings[-1].update(warningCount=len(observation["warnings"]),
                                             warningsSha256=checkpoint_hash(observation["warnings"]))
            result["monetaryObservation"] = {**(replay.monetary_observation or {
                "protocol": MONEY_OBSERVATION_PROTOCOL, "mode": request.semantic_money_mode, "warnings": []}),
                "workWarnings": work_warnings}
            if (invocation.manifest.get("hierarchy") and replay.current is not None
                    and replay.current.get("finalResult") != result):
                raise _invalid()
            if replay.fresh is not None:
                replay.fresh["finalResult"] = result
        except _Boundary as boundary:
            next_operation = boundary.operation
            if replay.fresh is not None and replay.fresh["status"] == "FAILED":
                replay.fresh["status"] = "REPAIR_REQUIRED"
        except _ProviderStopped:
            pass
        except AuditAiError as failure:
            if replay.fresh is None:
                raise
            failure_code = failure.code
            # The current provider call may already be fully validated when
            # constructing the *next* synthesis request hits its fixed bound.
            # Retain that analysis and its truthful successful call audit;
            # failureCode still prevents publishing an incomplete final result.
            validated_analysis = (
                failure.code in {"SEMANTIC_SYNTHESIS_INPUT_BOUND", "SEMANTIC_HIERARCHY_GROUP_INPUT_BOUND",
                    "SEMANTIC_HIERARCHY_FINAL_INPUT_BOUND", "SEMANTIC_HIERARCHY_DIGEST_BOUND",
                    "SEMANTIC_HIERARCHY_DIGEST_ALLOCATION_BOUND"}
                and replay.fresh["status"] == "VERIFIED"
                and replay.fresh.get("call", {}).get("outcome") == "SUCCEEDED"
                and (
                    isinstance(replay.fresh.get("validatedResult"), dict)
                    or (replay.fresh["purpose"] == "REPAIR"
                        and isinstance(replay.fresh.get("correctedResult"), dict))
                )
            )
            if not validated_analysis:
                replay.fresh["status"] = "FAILED"
        if replay.cursor != len(checkpoints):
            raise _invalid()
        if replay.fresh is not None:
            if replay.fresh["status"] == "FAILED" and replay.fresh.get("call", {}).get("outcome") == "FAILED":
                try:
                    _json(replay.fresh).encode("utf-8")
                except UnicodeEncodeError:
                    replay.fresh = _checkpoint_output_failure(replay.fresh, None, encoding_failure=True)
                    next_operation = result = None
            if replay.fresh.get("completion") is None:
                if replay.fresh.get("checkpointFailureCode") != "SEMANTIC_CHECKPOINT_ENCODING":
                    replay.fresh["completion"] = None
            replay.fresh.pop("checkpointHash", None)
            replay.fresh["checkpointHash"] = checkpoint_hash(replay.fresh)
            serialized = _json(replay.fresh).encode("utf-8")
            if len(serialized) > MAX_CHECKPOINT_BYTES:
                already_failed = (replay.fresh["status"] == "FAILED"
                                  and replay.fresh.get("call", {}).get("outcome") == "FAILED")
                replay.fresh = _checkpoint_output_failure(replay.fresh, serialized)
                if not already_failed:
                    failure_code = "SEMANTIC_CHECKPOINT_OUTPUT_BOUND"
                next_operation = result = None
        if getattr(replay,'synthetic_capture',None):
            store,bundle=replay.synthetic_capture
            packet=replay.fresh
            store.validate(bundle,{'valid':packet.get('status')=='VERIFIED',
                'status':packet.get('status'),'issues':packet.get('validationIssues',[]),
                'safeReason':packet.get('call',{}).get('safeReason'),
                'qualitySha256':checkpoint_hash(packet.get('quality')),
                'normalizedSha256':checkpoint_hash(packet.get('validatedResult')),
                'finalResultSha256':checkpoint_hash(result)})
        return {"checkpoint": replay.fresh, "next": next_operation, "result": result,
                "failureCode": failure_code}
