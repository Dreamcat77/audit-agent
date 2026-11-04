"""Lazy PaddleOCR 3.7 infrastructure adapter."""

from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
import importlib
import inspect
import logging
from pathlib import Path
import threading
import time
from typing import Any

from audit_ai.config import Settings
from audit_ai.ocr.port import OcrExtraction, OcrPage, OcrPort
from audit_ai.logging import log_event


_EXPECTED_VERSION = "3.7.0"
_LOGGER = logging.getLogger(__name__)
_CONSTRUCTOR_PARAMETERS = (
    "use_doc_orientation_classify",
    "use_doc_unwarping",
    "use_textline_orientation",
    "cpu_threads",
    "enable_mkldnn",
    "device",
)


class OcrRuntimeUnavailable(RuntimeError):
    """The installed PaddleOCR runtime does not match the probed contract."""


class OcrPredictionInvalid(RuntimeError):
    """PaddleOCR returned a shape outside the approved 3.x result contract."""


class PaddleOcrAdapter(OcrPort):
    """Load PaddleOCR on first extraction and serialize local CPU predictions."""

    def __init__(
        self,
        settings: Settings,
        *,
        module_loader: Callable[[str], Any] = importlib.import_module,
    ) -> None:
        self._settings = settings
        self._module_loader = module_loader
        self._pipeline: Any | None = None
        self._orientation_classifier: Any | None = None
        self._fast_primary = False
        self._accuracy_pipeline: Any | None = None
        self._initialization_lock = threading.Lock()
        self._prediction_lock = threading.Lock()

    def extract(self, path: Path) -> OcrExtraction:
        pipeline = self._get_pipeline()
        started = time.perf_counter_ns()
        prediction_started: int | None = None
        outcome = "FAILED"
        try:
            log_event(_LOGGER, "OCR_PREDICTION_WAIT", stage="OCR", outcome="WAIT")
            with self._prediction_lock:
                prediction_started = time.perf_counter_ns()
                log_event(_LOGGER, "OCR_PREDICT_START", stage="OCR", outcome="START")
                if self._settings.ocr_orientation_enabled:
                    from audit_ai.ocr.orientation import page_images, quality, recognize_page
                    page_results = []
                    scores = []
                    predict = self._bounded_image_predict(pipeline)
                    for position, image in enumerate(page_images(path), 1):
                        page, page_scores = recognize_page(image, position, predict, self._map_predictions,
                                                          self._orientation_classifier)
                        if self._fast_primary and (not page.text.strip() or page.confidence < .90):
                            log_event(_LOGGER,"OCR_ACCURACY_FALLBACK",stage="OCR",outcome="START",page_count=position)
                            if self._accuracy_pipeline is None:
                                self._accuracy_pipeline=self._construct_pipeline(self._module_loader("paddleocr").PaddleOCR)
                            # Re-read the original pixels with the accuracy model;
                            # never splice disagreeing rotations/amounts together.
                            reviewed,reviewed_scores=recognize_page(image,position,self._bounded_image_predict(self._accuracy_pipeline),
                                self._map_predictions,self._orientation_classifier)
                            if quality(reviewed,reviewed_scores)>=quality(page,page_scores):
                                page,page_scores=reviewed,reviewed_scores
                        page_results.append(page)
                        scores.extend(page_scores)
                    pages = tuple(page_results)
                else:
                    predictions = pipeline.predict(str(path))
                    # Paddle can return lazy results; mapping belongs to the
                    # same single slot as prediction, not outside the lock.
                    pages, scores = self._map_predictions(predictions)
            outcome = "SUCCESS"
        except OcrPredictionInvalid:
            raise
        except Exception as failure:
            raise OcrRuntimeUnavailable("PaddleOCR prediction failed safely") from failure
        finally:
            if prediction_started is not None:
                log_event(_LOGGER, "OCR_PREDICT_END", stage="OCR", outcome=outcome,
                          duration_ms=max(0, (time.perf_counter_ns() - prediction_started) // 1_000_000))
        duration_ms = max(0, (time.perf_counter_ns() - started) // 1_000_000)
        text = "\n".join(page.text for page in pages if page.text)
        confidence = sum(scores) / len(scores) if scores else 0.0
        return OcrExtraction(
            text=text,
            confidence=confidence,
            pages=pages,
            engine=f"PADDLEOCR_{_EXPECTED_VERSION}",
            duration_ms=duration_ms,
        )

    @staticmethod
    def _bounded_image_predict(pipeline: Any) -> Callable:
        # Probed PaddleOCR 3.7 supports max-side detection. The input is still
        # the full-resolution image; recognition crops use original pixels.
        for parameter in ("text_det_limit_side_len", "text_det_limit_type"):
            if not _accepts_parameter(pipeline.predict, parameter):
                raise OcrRuntimeUnavailable("PaddleOCR rotation runtime contract is not verified")
        return lambda image: pipeline.predict(image, text_det_limit_side_len=1920, text_det_limit_type="max")

    def _get_pipeline(self) -> Any:
        if self._pipeline is not None:
            return self._pipeline
        log_event(_LOGGER, "OCR_INITIALIZATION_WAIT", stage="OCR", outcome="WAIT")
        with self._initialization_lock:
            if self._pipeline is None:
                started = time.perf_counter_ns()
                outcome = "FAILED"
                log_event(_LOGGER, "OCR_INITIALIZATION_START", stage="OCR", outcome="START")
                try:
                    self._pipeline = self._initialize_pipeline()
                    outcome = "SUCCESS"
                finally:
                    log_event(_LOGGER, "OCR_INITIALIZATION_END", stage="OCR", outcome=outcome,
                              duration_ms=max(0, (time.perf_counter_ns() - started) // 1_000_000))
        return self._pipeline

    def _initialize_pipeline(self) -> Any:
        try:
            module = self._module_loader("paddleocr")
            pipeline_type = getattr(module, "PaddleOCR")
            if getattr(module, "__version__", None) != _EXPECTED_VERSION:
                raise OcrRuntimeUnavailable(
                    "PaddleOCR runtime contract is not verified"
                )
            for parameter in _CONSTRUCTOR_PARAMETERS:
                if not _accepts_parameter(pipeline_type, parameter):
                    raise OcrRuntimeUnavailable(
                        "PaddleOCR runtime contract is not verified"
                    )
            model_root=(Path(self._settings.ocr_fast_model_dir) if self._settings.ocr_fast_model_dir
                        else Path(__file__).resolve().parents[3]/"models")
            fast_models={"text_detection_model_name":"PP-OCRv5_mobile_det","text_recognition_model_name":"PP-OCRv4_mobile_rec"}
            ready=all((model_root/name/file).is_file() for name in fast_models.values()
                      for file in ("inference.json","inference.pdiparams","inference.yml"))
            if self._settings.ocr_fast_enabled and self._settings.ocr_fast_model_dir and not ready:
                raise OcrRuntimeUnavailable("OCR offline fast models are incomplete")
            self._fast_primary=self._settings.ocr_fast_enabled and ready
            options={}
            if self._fast_primary:
                options={**fast_models,"text_detection_model_dir":str(model_root/fast_models["text_detection_model_name"]),
                         "text_recognition_model_dir":str(model_root/fast_models["text_recognition_model_name"])}
                if any(not _accepts_parameter(pipeline_type,key) for key in options):
                    raise OcrRuntimeUnavailable("OCR offline fast model parameters are unsupported")
            pipeline = self._construct_pipeline(pipeline_type,**options)
            if self._settings.ocr_orientation_enabled:
                model_dir = (Path(self._settings.ocr_orientation_model_dir) if self._settings.ocr_orientation_model_dir
                             else Path(__file__).resolve().parents[3] / "models" / "PP-LCNet_x1_0_doc_ori")
                if model_dir.exists() or self._settings.ocr_orientation_model_dir:
                    if not all((model_dir / name).is_file() for name in
                               ("inference.json", "inference.pdiparams", "inference.yml")):
                        raise OcrRuntimeUnavailable("OCR orientation model is incomplete")
                    classifier_type = getattr(module, "DocImgOrientationClassification")
                    self._orientation_classifier = classifier_type(model_dir=str(model_dir), device="cpu",
                        cpu_threads=self._settings.ocr_cpu_threads, enable_mkldnn=self._settings.ocr_enable_mkldnn)
            predict = getattr(pipeline, "predict")
            if not callable(predict) or not _accepts_parameter(predict, "input"):
                raise OcrRuntimeUnavailable(
                    "PaddleOCR runtime contract is not verified"
                )
            return pipeline
        except OcrRuntimeUnavailable:
            raise
        except Exception as failure:
            raise OcrRuntimeUnavailable(
                "PaddleOCR runtime contract is not verified"
            ) from failure

    def _construct_pipeline(self,pipeline_type,**options):
        return pipeline_type(
                use_doc_orientation_classify=False,
                use_doc_unwarping=False,
                use_textline_orientation=False,
                cpu_threads=self._settings.ocr_cpu_threads,
                enable_mkldnn=self._settings.ocr_enable_mkldnn,
                device="cpu",
                **options,
            )

    def _map_predictions(
        self, predictions: Any
    ) -> tuple[tuple[OcrPage, ...], list[float]]:
        if not _is_sequence(predictions):
            raise OcrPredictionInvalid("invalid PaddleOCR prediction collection")
        pages: list[OcrPage] = []
        document_scores: list[float] = []
        for position, prediction in enumerate(predictions):
            payload = getattr(prediction, "json", None)
            if not isinstance(payload, Mapping):
                raise OcrPredictionInvalid("invalid PaddleOCR prediction result")
            result = payload.get("res")
            if not isinstance(result, Mapping):
                raise OcrPredictionInvalid("invalid PaddleOCR prediction result")
            texts = result.get("rec_texts")
            scores = result.get("rec_scores")
            if not _is_sequence(texts) or not _is_sequence(scores):
                raise OcrPredictionInvalid("invalid PaddleOCR text result")
            if len(texts) != len(scores):
                raise OcrPredictionInvalid("invalid PaddleOCR text result")
            page_texts: list[str] = []
            page_scores: list[float] = []
            for text, score in zip(texts, scores, strict=True):
                if not isinstance(text, str) or not _valid_score(score):
                    raise OcrPredictionInvalid("invalid PaddleOCR text result")
                normalized = text.strip()
                if normalized:
                    page_texts.append(normalized)
                    page_scores.append(float(score))
                    document_scores.append(float(score))
            page_index = result.get("page_index")
            if page_index is None:
                page_num = position + 1
            elif isinstance(page_index, int) and not isinstance(page_index, bool):
                page_num = page_index + 1
            else:
                raise OcrPredictionInvalid("invalid PaddleOCR page index")
            page_confidence = (
                sum(page_scores) / len(page_scores) if page_scores else 0.0
            )
            pages.append(
                OcrPage(
                    page_num=page_num,
                    text="\n".join(page_texts),
                    confidence=page_confidence,
                )
            )
        return tuple(pages), document_scores


def _accepts_parameter(callable_value: Any, name: str) -> bool:
    try:
        parameters = inspect.signature(callable_value).parameters.values()
    except (TypeError, ValueError):
        return False
    return any(
        parameter.name == name or parameter.kind == inspect.Parameter.VAR_KEYWORD
        for parameter in parameters
    )


def _is_sequence(value: Any) -> bool:
    return isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray))


def _valid_score(value: Any) -> bool:
    return (
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and 0.0 <= float(value) <= 1.0
    )
