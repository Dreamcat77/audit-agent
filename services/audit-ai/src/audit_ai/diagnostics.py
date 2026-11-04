"""Content-free pipeline lifecycle logs, independent of business auditing."""
from functools import wraps
import logging
import time
import hashlib
from uuid import uuid4

from audit_ai.logging import diagnostic_context, log_event

LOGGER = logging.getLogger("audit_ai.pipeline")


def diagnosed(operation):
    def decorate(function):
        @wraps(function)
        def wrapped(*args, **kwargs):
            # Works for bound model methods and the material router worker.
            request = args[-2] if len(args) >= 2 else kwargs.get("request")
            request_id = args[-1] if len(args) >= 2 else kwargs.get("request_id", "unavailable")
            counts = {}
            segments = getattr(request, "segments", None)
            materials = getattr(request, "materials", None)
            items = getattr(request, "items", None)
            if segments is not None:
                counts.update(segment_count=len(segments), input_chars=sum(len(s.text) for s in segments))
            if materials is not None:
                counts.update(material_count=len(materials), input_chars=sum(len(m.text) for m in materials))
            if items is not None:
                counts.update(item_count=len(items), input_chars=sum(len(i.description) for i in items))
            started = time.monotonic_ns()
            resource = getattr(request, "material_id", None) or getattr(request, "project_id", None) or getattr(request, "source_id", None)
            resource_fields = {"resource_id":str(resource)} if resource is not None else {}
            effective_operation = ("BUDGET_MATERIAL_EXTRACTION" if operation == "PROCUREMENT_MATERIAL_EXTRACTION"
                                   and getattr(request, "business_type", None) == "BUDGET" else operation)
            with diagnostic_context(request_id=request_id, operation=effective_operation):
                log_event(LOGGER, "PIPELINE_START", stage="APPLICATION", outcome="START", **counts, **resource_fields)
                try:
                    result = function(*args, **kwargs)
                except Exception as error:
                    code = getattr(error, "code", None)
                    log_event(LOGGER, "PIPELINE_END", stage="APPLICATION", outcome=code or "INTERNAL_ERROR",
                              error_class=type(error).__name__, duration_ms=(time.monotonic_ns()-started)//1_000_000, **resource_fields)
                    raise
                log_event(LOGGER, "PIPELINE_END", stage="APPLICATION", outcome="SUCCESS",
                          duration_ms=(time.monotonic_ns()-started)//1_000_000, **resource_fields)
                return result
        return wrapped
    return decorate


def provider_diagnosed(function):
    @wraps(function)
    def wrapped(self, **kwargs):
        settings = self._settings
        ready = all(v and v.strip() for v in (settings.model_base_url, settings.model_name, settings.model_api_key))
        with diagnostic_context(call_id=str(uuid4()), request_id=kwargs["request_id"],
                                operation=kwargs["operation"], purpose=kwargs["purpose"]):
            log_event(LOGGER, "PROVIDER_CALL_START", stage="MODEL_HTTP", outcome="START",
                      configuration_ready="YES" if ready else "NO",
                      endpoint_fingerprint=hashlib.sha256((settings.model_base_url or "").encode()).hexdigest()[:24],
                      model_fingerprint=hashlib.sha256((settings.model_name or "").encode()).hexdigest()[:24],
                      transport=kwargs.get("structured_transport", "PROMPT_ONLY"),
                      prompt_chars=len(kwargs["system_prompt"]), user_chars=len(kwargs["user_content"]),
                      max_output_tokens=kwargs.get("max_output_tokens") or 0,
                      thinking_disabled="YES" if kwargs.get("disable_thinking",False) else "NO",
                      remaining_budget_ms=max(0,int((kwargs.get("provider_timeout_seconds") or 0)*1000)),
                      attempt=kwargs["attempt"], max_attempts=kwargs["max_attempts"])
            started = time.monotonic_ns()
            try:
                result = function(self, **kwargs)
            except Exception as error:
                log_event(LOGGER, "PROVIDER_CALL_END", stage="MODEL_HTTP", outcome="FAILED",
                          error_class=type(error).__name__, retryable="YES" if getattr(error,"retryable",False) else "NO",
                          duration_ms=(time.monotonic_ns()-started)//1_000_000)
                raise
            log_event(LOGGER, "PROVIDER_CALL_END", stage="MODEL_HTTP", outcome="SUCCESS",
                      duration_ms=(time.monotonic_ns()-started)//1_000_000,
                      provider_finish_reason=result.finish_reason or "MISSING",
                      **{key: value for key in ("prompt_tokens", "completion_tokens", "total_tokens")
                         if (value := getattr(result, key)) is not None})
            return result
    return wrapped
