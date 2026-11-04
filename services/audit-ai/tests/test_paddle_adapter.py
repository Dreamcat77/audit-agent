from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from audit_ai.logging import diagnostic_context

from audit_ai.config import Settings
from audit_ai.ocr.paddle_adapter import (
    OcrRuntimeUnavailable,
    PaddleOcrAdapter,
)


class FakePrediction:
    def __init__(self, page_index: int | None, texts: list[str], scores: list[float]):
        self.json = {
            "res": {
                "page_index": page_index,
                "rec_texts": texts,
                "rec_scores": scores,
            }
        }


class RecordingPaddleOcr:
    constructor_calls: list[dict[str, Any]] = []
    predict_calls: list[str] = []

    def __init__(
        self,
        *,
        use_doc_orientation_classify: bool,
        use_doc_unwarping: bool,
        use_textline_orientation: bool,
        cpu_threads: int,
        enable_mkldnn: bool,
        device: str,
    ) -> None:
        self.constructor_calls.append(
            {
                "use_doc_orientation_classify": use_doc_orientation_classify,
                "use_doc_unwarping": use_doc_unwarping,
                "use_textline_orientation": use_textline_orientation,
                "cpu_threads": cpu_threads,
                "enable_mkldnn": enable_mkldnn,
                "device": device,
            }
        )

    def predict(self, input: str) -> list[FakePrediction]:
        self.predict_calls.append(input)
        return [
            FakePrediction(0, ["合同名称", "建设工程"], [0.9, 0.8]),
            FakePrediction(1, ["工期约定", ""], [0.7, 0.1]),
        ]


@pytest.fixture(autouse=True)
def clear_recording_pipeline() -> None:
    RecordingPaddleOcr.constructor_calls.clear()
    RecordingPaddleOcr.predict_calls.clear()


def test_adapter_lazy_loads_once_and_maps_only_3x_prediction_shape(
    tmp_path: Path,
) -> None:
    load_calls: list[str] = []

    def load_module(name: str) -> Any:
        load_calls.append(name)
        return SimpleNamespace(__version__="3.7.0", PaddleOCR=RecordingPaddleOcr)

    adapter = PaddleOcrAdapter(Settings(ocr_orientation_enabled=False), module_loader=load_module)
    document = tmp_path / "scan.pdf"
    document.write_bytes(b"%PDF-test")

    assert load_calls == []
    first = adapter.extract(document)
    second = adapter.extract(document)

    assert load_calls == ["paddleocr"]
    assert len(RecordingPaddleOcr.constructor_calls) == 1
    assert RecordingPaddleOcr.constructor_calls[0] == {
        "use_doc_orientation_classify": False,
        "use_doc_unwarping": False,
        "use_textline_orientation": False,
        "cpu_threads": 1,
        "enable_mkldnn": False,
        "device": "cpu",
    }
    assert RecordingPaddleOcr.predict_calls == [str(document), str(document)]
    assert first.engine == "PADDLEOCR_3.7.0"
    assert first.text == "合同名称\n建设工程\n工期约定"
    assert first.confidence == pytest.approx(0.8)
    assert [(page.page_num, page.text, page.confidence) for page in first.pages] == [
        (1, "合同名称\n建设工程", pytest.approx(0.85)),
        (2, "工期约定", pytest.approx(0.7)),
    ]
    assert first.duration_ms >= 0
    assert second.text == first.text


def test_adapter_rejects_unprobed_version_instead_of_using_legacy_fallback(
    tmp_path: Path,
) -> None:
    module = SimpleNamespace(__version__="2.7.3", PaddleOCR=RecordingPaddleOcr)
    adapter = PaddleOcrAdapter(Settings(ocr_orientation_enabled=False), module_loader=lambda _name: module)

    with pytest.raises(OcrRuntimeUnavailable, match="runtime contract is not verified"):
        adapter.extract(tmp_path / "scan.png")


def test_adapter_rejects_constructor_without_3x_parameters(tmp_path: Path) -> None:
    class LegacyPipeline:
        def __init__(self, use_angle_cls: bool = False) -> None:
            self.use_angle_cls = use_angle_cls

        def ocr(self, path: str) -> list[Any]:
            return []

    module = SimpleNamespace(__version__="3.7.0", PaddleOCR=LegacyPipeline)
    adapter = PaddleOcrAdapter(Settings(ocr_orientation_enabled=False), module_loader=lambda _name: module)

    with pytest.raises(OcrRuntimeUnavailable, match="runtime contract is not verified"):
        adapter.extract(tmp_path / "scan.png")


def test_prediction_stage_brackets_real_call_and_lazy_consumption_without_source_logs(
    tmp_path: Path, caplog: pytest.LogCaptureFixture,
) -> None:
    class LazyPage:
        @property
        def json(self):
            assert "OCR_PREDICT_START" in caplog.text
            assert "OCR_PREDICT_END" not in caplog.text
            return FakePrediction(0, ["private-source-marker"], [0.9]).json

    class ObservedPipeline(RecordingPaddleOcr):
        def predict(self, input: str):
            assert "OCR_INITIALIZATION_END" in caplog.text
            assert "OCR_PREDICTION_WAIT" in caplog.text
            assert "OCR_PREDICT_START" in caplog.text
            return [LazyPage()]

    caplog.set_level("INFO")
    adapter = PaddleOcrAdapter(Settings(ocr_orientation_enabled=False), module_loader=lambda _: SimpleNamespace(
        __version__="3.7.0", PaddleOCR=ObservedPipeline))
    with diagnostic_context(request_id="ocr-stage-regression"):
        result = adapter.extract(tmp_path / "private-path-marker.pdf")
    assert result.text == "private-source-marker"
    assert "OCR_PREDICT_END" in caplog.text
    assert "request_id=ocr-stage-regression" in caplog.text
    assert "outcome=SUCCESS" in caplog.text
    assert "private-path-marker" not in caplog.text
    assert "private-source-marker" not in caplog.text
    caplog.clear()
    # Already loaded pipeline is not initialized anew to obtain stage proof.
    class ReusedPipeline:
        def predict(self, input: str):
            return [FakePrediction(0, ["safe"], [.9])]
    adapter._pipeline = ReusedPipeline()
    adapter.extract(tmp_path / "private-path-marker.pdf")
    assert "OCR_INITIALIZATION_START" not in caplog.text
    assert "OCR_PREDICT_START" in caplog.text
    assert "OCR_PREDICT_END" in caplog.text


def test_prediction_stage_failure_is_closed_without_logging_error_details(
    tmp_path: Path, caplog: pytest.LogCaptureFixture,
) -> None:
    class BrokenPipeline(RecordingPaddleOcr):
        def predict(self, input: str):
            raise RuntimeError("private-provider-marker")
    caplog.set_level("INFO")
    adapter = PaddleOcrAdapter(Settings(ocr_orientation_enabled=False), module_loader=lambda _: SimpleNamespace(
        __version__="3.7.0", PaddleOCR=BrokenPipeline))
    with pytest.raises(OcrRuntimeUnavailable):
        adapter.extract(tmp_path / "private-path-marker.pdf")
    assert "OCR_PREDICT_START" in caplog.text
    assert "OCR_PREDICT_END" in caplog.text
    assert "outcome=FAILED" in caplog.text
    assert "private-provider-marker" not in caplog.text
    assert "private-path-marker" not in caplog.text
