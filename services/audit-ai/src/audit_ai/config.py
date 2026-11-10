"""Environment-backed service settings with fail-closed OCR invariants."""

from __future__ import annotations

from dataclasses import dataclass, field
import math
import os


def _environment_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    normalized = value.strip().lower()
    if normalized in {"true", "1", "yes"}:
        return True
    if normalized in {"false", "0", "no"}:
        return False
    raise ValueError(f"{name} must be a boolean")


def _environment_int(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None:
        return default
    try:
        return int(value)
    except ValueError as failure:
        raise ValueError(f"{name} must be an integer") from failure


def _environment_float(name: str, default: float) -> float:
    value = os.getenv(name)
    if value is None:
        return default
    try:
        return float(value)
    except ValueError as failure:
        raise ValueError(f"{name} must be a number") from failure


def _environment_optional_float(name: str) -> float | None:
    value = os.getenv(name)
    if value is None or not value.strip():
        return None
    try:
        return float(value)
    except ValueError as failure:
        raise ValueError(f"{name} must be a number") from failure


@dataclass(frozen=True, slots=True)
class Settings:
    """Runtime settings that are safe to construct without optional AI packages."""

    ocr_cpu_threads: int = 1
    ocr_enable_mkldnn: bool = False
    ocr_orientation_enabled: bool = True
    ocr_orientation_model_dir: str | None = None
    ocr_fast_enabled: bool = True
    ocr_fast_model_dir: str | None = None
    ocr_process_isolation: bool = False
    ocr_worker_processes: int = 1
    ocr_memory_budget_mb: int = 16384
    ocr_memory_reserve_mb: int = 4096
    ocr_worker_memory_mb: int = 4096
    max_upload_bytes: int = 50 * 1024 * 1024
    ocr_temp_root: str | None = None
    model_base_url: str | None = None
    model_api_key: str | None = field(default=None, repr=False)
    model_name: str | None = None
    client_identity_required: bool = False
    model_connect_timeout_seconds: float = 5.0
    model_read_timeout_seconds: float = 60.0
    contract_model_read_timeout_seconds: float = 600.0
    case_model_read_timeout_seconds: float = 150.0
    case_output_tokens: int = 4096
    semantic_structured_transport: str = "PROMPT_ONLY"
    model_total_budget_seconds: float = 900.0
    model_chunk_chars: int = 6000
    model_max_chunks: int = 20
    tender_max_chunks: int = 160
    material_chunk_chars: int = 3200
    material_chunk_bytes: int = 8000
    material_context_chars: int = 96
    material_output_tokens: int = 1536
    material_model_read_timeout_seconds: float = 180.0
    material_checkpoint_root: str | None = None
    material_checkpoint_ttl_seconds: int = 900
    demo_relax_breach_monetary: bool = False
    contract_semantic_money_mode: str = "STRICT"
    contract_semantic_observe_project_ids: tuple[str, ...] = ()
    document_role_enabled: bool = False
    contract_fact_extraction_enabled: bool = False

    def __post_init__(self) -> None:
        if self.contract_semantic_money_mode not in {"STRICT", "OBSERVE"}:
            raise ValueError("contract semantic money mode must be STRICT or OBSERVE")
        if (self.contract_semantic_money_mode == "OBSERVE"
                and (not self.contract_semantic_observe_project_ids or self.demo_relax_breach_monetary)):
            raise ValueError("OBSERVE requires explicit projects and cannot use the legacy demo flag")
        if (len(set(self.contract_semantic_observe_project_ids)) != len(self.contract_semantic_observe_project_ids)
                or any(not isinstance(p, str) or not p.strip() or p != p.strip() or len(p) > 100
                       for p in self.contract_semantic_observe_project_ids)):
            raise ValueError("contract semantic observe projects are invalid")
        if not isinstance(self.ocr_orientation_enabled, bool):
            raise ValueError("ocr_orientation_enabled must be boolean")
        if not isinstance(self.ocr_fast_enabled, bool):
            raise ValueError("ocr_fast_enabled must be boolean")
        if not isinstance(self.ocr_process_isolation, bool):
            raise ValueError("ocr_process_isolation must be boolean")
        if type(self.ocr_worker_processes) is not int or not 1 <= self.ocr_worker_processes <= 4:
            raise ValueError("ocr_worker_processes must be between one and four")
        for name in ('ocr_memory_budget_mb','ocr_memory_reserve_mb','ocr_worker_memory_mb'):
            if type(getattr(self,name)) is not int or getattr(self,name)<512:
                raise ValueError(name+' must be an integer of at least 512 MB')
        if self.ocr_cpu_threads != 1 or self.ocr_enable_mkldnn:
            raise ValueError(
                "OCR runtime safety invariant requires one CPU thread and MKLDNN disabled"
            )
        if self.max_upload_bytes <= 0:
            raise ValueError("max_upload_bytes must be positive")
        if self.model_connect_timeout_seconds <= 0:
            raise ValueError("model_connect_timeout_seconds must be positive")
        if self.model_read_timeout_seconds <= 0:
            raise ValueError("model_read_timeout_seconds must be positive")
        if not math.isfinite(self.case_model_read_timeout_seconds) or not 1 <= self.case_model_read_timeout_seconds <= 150:
            raise ValueError("case_model_read_timeout_seconds must be between 1 and 150")
        if not 256 <= self.case_output_tokens <= 4096:
            raise ValueError("case_output_tokens must be between 256 and 4096")
        if self.contract_model_read_timeout_seconds <= 0:
            raise ValueError(
                "contract_model_read_timeout_seconds must be positive"
            )
        if self.semantic_structured_transport not in {
            "PROMPT_ONLY",
            "OPENAI_COMPAT_STRUCTURED",
            "OLLAMA_NATIVE_STRUCTURED",
        }:
            raise ValueError("semantic_structured_transport is invalid")
        if (
            self.model_total_budget_seconds is not None
            and (
                not math.isfinite(self.model_total_budget_seconds)
                or self.model_total_budget_seconds <= 0
            )
        ):
            raise ValueError(
                "model_total_budget_seconds must be finite and positive"
            )
        if self.model_chunk_chars <= 0 or self.model_max_chunks <= 0:
            raise ValueError("model chunk bounds must be positive")
        if not 1 <= self.tender_max_chunks <= 256:
            raise ValueError("tender_max_chunks must be between 1 and 256")
        if not 512 <= self.material_chunk_chars <= 3200:
            raise ValueError("material_chunk_chars must be between 512 and 3200")
        if not 1024 <= self.material_chunk_bytes <= 16000:
            raise ValueError("material_chunk_bytes must be between 1024 and 16000")
        if not 0 <= self.material_context_chars <= 192:
            raise ValueError("material_context_chars must be between 0 and 192")
        if not 256 <= self.material_output_tokens <= 4096:
            raise ValueError("material_output_tokens must be between 256 and 4096")
        if not math.isfinite(self.material_model_read_timeout_seconds) or not 1 <= self.material_model_read_timeout_seconds <= 180:
            raise ValueError("material_model_read_timeout_seconds must be between 1 and 180")
        if not 60 <= self.material_checkpoint_ttl_seconds <= 3600:
            raise ValueError("material_checkpoint_ttl_seconds must be between 60 and 3600")

    @classmethod
    def from_env(cls) -> "Settings":
        """Load only named settings without enumerating or exposing the environment."""

        return cls(
            ocr_cpu_threads=_environment_int("AUDIT_OCR_CPU_THREADS", 1),
            ocr_enable_mkldnn=_environment_bool(
                "AUDIT_OCR_ENABLE_MKLDNN", False
            ),
            ocr_orientation_enabled=_environment_bool("AUDIT_OCR_ORIENTATION_ENABLED", True),
            ocr_orientation_model_dir=os.getenv("AUDIT_OCR_ORIENTATION_MODEL_DIR") or None,
            ocr_fast_enabled=_environment_bool("AUDIT_OCR_FAST_ENABLED", True),
            ocr_fast_model_dir=os.getenv("AUDIT_OCR_FAST_MODEL_DIR") or None,
            ocr_process_isolation=_environment_bool("AUDIT_OCR_PROCESS_ISOLATION", False),
            ocr_worker_processes=_environment_int("AUDIT_OCR_WORKER_PROCESSES", 1),
            ocr_memory_budget_mb=_environment_int("AUDIT_OCR_MEMORY_BUDGET_MB", 16384),
            ocr_memory_reserve_mb=_environment_int("AUDIT_OCR_MEMORY_RESERVE_MB", 4096),
            ocr_worker_memory_mb=_environment_int("AUDIT_OCR_WORKER_MEMORY_MB", 4096),
            max_upload_bytes=_environment_int(
                "AUDIT_AI_MAX_UPLOAD_BYTES", 50 * 1024 * 1024
            ),
            ocr_temp_root=os.getenv("AUDIT_AI_TEMP_ROOT") or None,
            model_base_url=os.getenv("AUDIT_MODEL_BASE_URL") or None,
            model_api_key=os.getenv("AUDIT_MODEL_API_KEY") or None,
            model_name=os.getenv("AUDIT_MODEL_NAME") or None,
            client_identity_required=_environment_bool("AUDIT_CLIENT_IDENTITY_REQUIRED", False),
            model_connect_timeout_seconds=_environment_float(
                "AUDIT_MODEL_CONNECT_TIMEOUT_SECONDS", 5.0
            ),
            model_read_timeout_seconds=_environment_float(
                "AUDIT_MODEL_READ_TIMEOUT_SECONDS", 60.0
            ),
            contract_model_read_timeout_seconds=_environment_float(
                "AUDIT_CONTRACT_MODEL_READ_TIMEOUT_SECONDS", 600.0
            ),
            case_model_read_timeout_seconds=_environment_float("AUDIT_CASE_MODEL_READ_TIMEOUT_SECONDS", 150.0),
            case_output_tokens=_environment_int("AUDIT_CASE_OUTPUT_TOKENS", 4096),
            semantic_structured_transport=(
                os.getenv("AUDIT_SEMANTIC_STRUCTURED_TRANSPORT")
                or "PROMPT_ONLY"
            ).strip().upper(),
            model_total_budget_seconds=_environment_float(
                "AUDIT_MODEL_TOTAL_BUDGET_SECONDS", 900.0
            ),
            model_chunk_chars=_environment_int("AUDIT_MODEL_CHUNK_CHARS", 6000),
            model_max_chunks=_environment_int("AUDIT_MODEL_MAX_CHUNKS", 20),
            tender_max_chunks=_environment_int("AUDIT_TENDER_MAX_CHUNKS", 160),
            material_chunk_chars=_environment_int("AUDIT_MATERIAL_CHUNK_CHARS", 3200),
            material_chunk_bytes=_environment_int("AUDIT_MATERIAL_CHUNK_BYTES", 8000),
            material_context_chars=_environment_int("AUDIT_MATERIAL_CONTEXT_CHARS", 96),
            material_output_tokens=_environment_int("AUDIT_MATERIAL_OUTPUT_TOKENS", 1536),
            material_model_read_timeout_seconds=_environment_float("AUDIT_MATERIAL_MODEL_READ_TIMEOUT_SECONDS", 180.0),
            material_checkpoint_root=os.getenv("AUDIT_MATERIAL_CHECKPOINT_ROOT") or None,
            material_checkpoint_ttl_seconds=_environment_int("AUDIT_MATERIAL_CHECKPOINT_TTL_SECONDS", 900),
            demo_relax_breach_monetary=_environment_bool(
                "AUDIT_MODEL_DEMO_RELAX_BREACH_MONETARY", False
            ),
            contract_semantic_money_mode=(os.getenv("AUDIT_CONTRACT_SEMANTIC_MONEY_MODE") or "STRICT").strip().upper(),
            contract_semantic_observe_project_ids=tuple(p.strip() for p in
                (os.getenv("AUDIT_CONTRACT_SEMANTIC_OBSERVE_PROJECT_IDS") or "").split(",") if p.strip()),
            document_role_enabled=_environment_bool(
                "AUDIT_AI_DOCUMENT_ROLE_ENABLED", False
            ),
            contract_fact_extraction_enabled=_environment_bool(
                "AUDIT_AI_CONTRACT_FACT_EXTRACTION_ENABLED", False
            ),
        )
