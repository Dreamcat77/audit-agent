from pathlib import Path

import pytest

from audit_ai.config import Settings


def test_safe_ocr_runtime_settings_are_the_defaults() -> None:
    settings = Settings()

    assert settings.ocr_cpu_threads == 1
    assert settings.ocr_enable_mkldnn is False
    assert settings.max_upload_bytes == 50 * 1024 * 1024


def test_tender_chunk_budget_is_independent_from_contract_budget(monkeypatch):
    monkeypatch.setenv('AUDIT_TENDER_MAX_CHUNKS','120')
    settings=Settings.from_env()
    assert settings.tender_max_chunks==120 and settings.model_max_chunks==20
    for invalid in (0,257):
        with pytest.raises(ValueError,match='tender_max_chunks'):
            Settings(tender_max_chunks=invalid)


def test_material_bounds_load_without_changing_model_connection(monkeypatch):
    monkeypatch.setenv('AUDIT_MATERIAL_CHUNK_CHARS','1600')
    monkeypatch.setenv('AUDIT_MATERIAL_CHUNK_BYTES','4000')
    monkeypatch.setenv('AUDIT_MATERIAL_CONTEXT_CHARS','48')
    monkeypatch.setenv('AUDIT_MATERIAL_OUTPUT_TOKENS','768')
    monkeypatch.setenv('AUDIT_MATERIAL_CHECKPOINT_TTL_SECONDS','600')
    settings=Settings.from_env()
    assert (settings.material_chunk_chars,settings.material_chunk_bytes,settings.material_context_chars)==(1600,4000,48)
    assert settings.material_output_tokens==768 and settings.material_checkpoint_ttl_seconds==600


def test_model_timeout_defaults_separate_global_and_contract_semantic_budgets() -> None:
    settings = Settings()

    assert settings.model_connect_timeout_seconds == 5.0
    assert settings.model_read_timeout_seconds == 60.0
    assert settings.contract_model_read_timeout_seconds == 600.0
    assert settings.model_total_budget_seconds == 900.0
    assert (
        settings.contract_model_read_timeout_seconds
        < settings.model_total_budget_seconds
    )


def test_contract_model_timeouts_are_loaded_from_environment(monkeypatch) -> None:
    monkeypatch.setenv("AUDIT_CONTRACT_MODEL_READ_TIMEOUT_SECONDS", "700.25")
    monkeypatch.setenv("AUDIT_MODEL_TOTAL_BUDGET_SECONDS", "1050.5")

    settings = Settings.from_env()

    assert settings.contract_model_read_timeout_seconds == 700.25
    assert settings.model_total_budget_seconds == 1050.5


def test_semantic_structured_transport_is_explicit_and_fail_closed(monkeypatch) -> None:
    assert Settings().semantic_structured_transport == "PROMPT_ONLY"
    monkeypatch.setenv(
        "AUDIT_SEMANTIC_STRUCTURED_TRANSPORT", "ollama_native_structured"
    )
    assert (
        Settings.from_env().semantic_structured_transport
        == "OLLAMA_NATIVE_STRUCTURED"
    )
    monkeypatch.setenv("AUDIT_SEMANTIC_STRUCTURED_TRANSPORT", "unverified")
    with pytest.raises(ValueError, match="semantic_structured_transport"):
        Settings.from_env()


def test_repository_timeout_defaults_form_the_documented_hierarchy() -> None:
    example_path = Path(__file__).resolve().parents[3] / ".env.example"
    values = {
        key: value
        for line in example_path.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#") and "=" in line
        for key, value in (line.split("=", 1),)
    }

    global_read_seconds = float(values["AUDIT_MODEL_READ_TIMEOUT_SECONDS"])
    contract_read_seconds = float(
        values["AUDIT_CONTRACT_MODEL_READ_TIMEOUT_SECONDS"]
    )
    contract_total_seconds = float(values["AUDIT_MODEL_TOTAL_BUDGET_SECONDS"])
    backend_read_seconds = float(values["AUDIT_AI_READ_TIMEOUT_MS"]) / 1000
    worker_timeout = values["AUDIT_CONTRACT_REVIEW_WORKER_TIMEOUT"]
    assert worker_timeout.startswith("PT") and worker_timeout.endswith("M")
    worker_seconds = float(worker_timeout[2:-1]) * 60

    assert global_read_seconds == 60
    assert (
        contract_read_seconds
        < contract_total_seconds
        < backend_read_seconds
        < worker_seconds
    )


@pytest.mark.parametrize("value", [0, -1, float("nan"), float("inf")])
def test_model_total_budget_must_be_finite_and_positive(value: float) -> None:
    with pytest.raises(ValueError, match="model_total_budget_seconds"):
        Settings(model_total_budget_seconds=value)


def test_demo_breach_monetary_relaxation_is_disabled_by_default() -> None:
    assert Settings().demo_relax_breach_monetary is False


def test_contract_fact_extraction_is_disabled_by_default_and_can_be_enabled(monkeypatch) -> None:
    assert Settings().contract_fact_extraction_enabled is False
    monkeypatch.setenv("AUDIT_AI_CONTRACT_FACT_EXTRACTION_ENABLED", "true")
    assert Settings.from_env().contract_fact_extraction_enabled is True


@pytest.mark.parametrize("enabled", ["true", "1", "yes"])
def test_environment_can_explicitly_enable_demo_breach_monetary_relaxation(
    monkeypatch: pytest.MonkeyPatch,
    enabled: str,
) -> None:
    monkeypatch.setenv("AUDIT_MODEL_DEMO_RELAX_BREACH_MONETARY", enabled)

    assert Settings.from_env().demo_relax_breach_monetary is True


@pytest.mark.parametrize(
    ("threads", "mkldnn"),
    [(0, False), (2, False), (1, True)],
)
def test_unsafe_ocr_runtime_settings_are_rejected(threads: int, mkldnn: bool) -> None:
    with pytest.raises(ValueError, match="OCR runtime safety invariant"):
        Settings(ocr_cpu_threads=threads, ocr_enable_mkldnn=mkldnn)


def test_environment_loader_rejects_invalid_boolean(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AUDIT_OCR_ENABLE_MKLDNN", "sometimes")

    with pytest.raises(ValueError, match="AUDIT_OCR_ENABLE_MKLDNN"):
        Settings.from_env()
