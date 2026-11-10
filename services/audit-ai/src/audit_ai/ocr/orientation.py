"""Bounded per-page orientation with local weights and unchanged source files."""
from __future__ import annotations

from collections.abc import Callable, Iterator
import logging
from pathlib import Path
import time
from typing import Any

from audit_ai.logging import log_event
from audit_ai.ocr.port import OcrPage

_LOGGER = logging.getLogger(__name__)
MAX_PIXELS = 40_000_000
MAX_PAGES = 1024


def page_images(path: Path) -> Iterator[Any]:
    # Native dependencies stay lazy; health checks never initialize OCR.
    from PIL import Image, ImageOps

    if path.suffix.lower() == ".pdf":
        import pypdfium2 as pdfium

        with pdfium.PdfDocument(str(path)) as document:
            if len(document) > MAX_PAGES:
                raise ValueError("OCR page limit exceeded")
            for index in range(len(document)):
                page = document[index]
                bitmap = None
                image = None
                try:
                    width, height = page.get_size()
                    if width <= 0 or height <= 0:
                        raise ValueError("OCR page dimensions invalid")
                    scale = min(240 / 72, (MAX_PIXELS / (width * height)) ** .5 * .99)
                    bitmap = page.render(scale=scale)
                    image = bitmap.to_pil().convert("RGB")
                    yield image
                finally:
                    if image is not None:
                        image.close()
                    if bitmap is not None:
                        bitmap.close()
                    page.close()
        return

    with Image.open(path) as source:
        frames = getattr(source, "n_frames", 1)
        if frames > MAX_PAGES:
            raise ValueError("OCR page limit exceeded")
        for index in range(frames):
            source.seek(index)
            if source.width * source.height > MAX_PIXELS:
                raise ValueError("OCR pixel limit exceeded")
            with ImageOps.exif_transpose(source) as oriented:
                # Composite transparency onto paper instead of turning it black.
                with oriented.convert("RGBA") as rgba:
                    with Image.new("RGBA", rgba.size, "white") as paper:
                        paper.alpha_composite(rgba)
                        with paper.convert("RGB") as image:
                            yield image


def quality(page: OcrPage, scores: list[float]) -> float:
    # Confidence alone would prefer a single confidently recognized logo over a
    # full page. Weight legible character coverage and confidence together.
    lines = page.text.splitlines()
    if not scores:
        return 0.
    if len(lines) != len(scores):
        # A provider text region may itself contain line breaks.
        return sum(not c.isspace() for c in page.text) * sum(s ** 3 if s >= .5 else 0. for s in scores) / len(scores)
    return sum(sum(not c.isspace() for c in line) * score ** 3
               for line, score in zip(lines, scores, strict=True) if score >= .5)


def deskew_angle(image: Any) -> float:
    import cv2
    import numpy as np

    # Only small, consistently supported line tilts are considered. No warp
    # model, no guessed perspective correction, no expansion of OCR concurrency.
    small = image.copy()
    try:
        small.thumbnail((1600, 1600))
        gray = cv2.cvtColor(np.asarray(small), cv2.COLOR_RGB2GRAY)
        edges = cv2.Canny(gray, 70, 160)
        lines = cv2.HoughLinesP(edges, 1, np.pi / 1800, 60,
                               minLineLength=max(50, small.width // 7), maxLineGap=12)
        if lines is None:
            return 0.
        angles = []
        for x1, y1, x2, y2 in lines[:, 0]:
            angle = float(np.degrees(np.arctan2(y2-y1, x2-x1)))
            if abs(angle) <= 12:
                angles.append(angle)
        if len(angles) < 3:
            return 0.
        median = float(np.median(angles))
        if .7 <= abs(median) <= 12 and sum(abs(a-median) < 1 for a in angles) >= 3:
            return median
        return 0.
    finally:
        small.close()


def recognize_page(image: Any, page_num: int, predict: Callable,
                   map_predictions: Callable, classifier: Any = None) -> tuple[OcrPage, list[float]]:
    import numpy as np
    from PIL import Image

    def recognize(candidate):
        # Fully consume each prediction while the caller holds the OCR slot.
        pages, scores = map_predictions(predict(np.asarray(candidate)[:, :, ::-1].copy()))
        if len(pages) != 1:
            raise ValueError("OCR rotation page coverage invalid")
        return pages[0], scores

    started = time.perf_counter_ns()
    best_page = None
    full_page = None
    full_scores = None
    full_angle = None
    candidate_count = 0
    classifier_confidence = 0.0
    if classifier is not None:
        with image.copy() as preview:
            preview.thumbnail((896, 896))
            # Paddle's public image adapters expect BGR arrays.
            predictions = list(classifier.predict(np.asarray(preview)[:, :, ::-1].copy()))
        if len(predictions) != 1:
            raise ValueError("OCR direction classification coverage invalid")
        payload = getattr(predictions[0], "json", {}).get("res", {})
        labels, probabilities = payload.get("label_names"), payload.get("scores")
        if (not isinstance(labels, (tuple, list)) or not labels or labels[0] not in ("0","90","180","270")
                or not isinstance(probabilities, (tuple, list)) or len(probabilities) != len(labels)
                or isinstance(probabilities[0], bool) or not isinstance(probabilities[0], (int,float))
                or not 0 <= probabilities[0] <= 1):
            raise ValueError("OCR direction classification result invalid")
        classifier_confidence = float(probabilities[0])
        if classifier_confidence >= .85:
            best_angle = int(labels[0])
            with image.rotate(best_angle, expand=True) as candidate:
                best_page, best_scores = recognize(candidate)
            full_page, full_scores, full_angle = best_page, best_scores, best_angle
            candidate_count += 1
            # A short, confidently read title is valid evidence. Do not discard
            # it or run four more probes solely because it has fewer characters.
            if best_page.confidence < .8:
                best_page = None
    if best_page is None:
        # Missing/uncertain classifier uses bounded OCR probes, not blind
        # rotation. Never download a model while handling a user upload.
        with image.copy() as probe:
            probe.thumbnail((896, 896))
            best_page, best_scores = recognize(probe)
            best_quality = quality(best_page, best_scores)
            best_angle = 0
            for angle in (90, 180, 270):
                with probe.rotate(angle, expand=True) as candidate:
                    page, scores = recognize(candidate)
                candidate_quality = quality(page, scores)
                if candidate_quality > best_quality * 1.02 + .01:
                    best_page, best_scores, best_quality, best_angle = page, scores, candidate_quality, angle
            candidate_count += 4
            if full_angle == best_angle:
                best_page, best_scores = full_page, full_scores
            elif probe.size != image.size:
                with image.rotate(best_angle, expand=True) as candidate:
                    best_page, best_scores = recognize(candidate)
                candidate_count += 1
            # Direction probes must not replace an already-read full page with
            # a weaker fragment. Keep one coherent orientation, never concatenate.
            if full_page is not None and quality(full_page, full_scores) > quality(best_page, best_scores) * 1.02 + .01:
                best_page, best_scores, best_angle = full_page, full_scores, full_angle
    best_quality = quality(best_page, best_scores)
    with image.rotate(best_angle, expand=True) as upright:
        rotations = [(best_angle, image.size, upright.size)]
        tilt = deskew_angle(upright)
        if tilt:
            with upright.rotate(tilt, resample=Image.Resampling.BICUBIC,
                                expand=True, fillcolor="white") as candidate:
                if candidate.width * candidate.height <= MAX_PIXELS:
                    candidate_count += 1
                    page, scores = recognize(candidate)
                    score = quality(page, scores)
                    if score > best_quality * 1.02 + .01:
                        best_page, best_scores, best_quality = page, scores, score
                        rotations.append((tilt, upright.size, candidate.size))
                    else:
                        tilt = 0.
                else:
                    tilt = 0.
    log_event(_LOGGER, "OCR_ORIENTATION_SELECTED", stage="OCR", outcome="SUCCESS",
              page_count=page_num, rotation_degrees=best_angle, deskew_millidegrees=round(abs(tilt) * 1000),
              input_chars=len(best_page.text), orientation_candidates=candidate_count,
              duration_ms=max(0, (time.perf_counter_ns() - started) // 1_000_000),
              orientation_quality_milli=round(max(0., best_quality) * 1000),
              classifier_confidence_milli=round(classifier_confidence * 1000), ocr_page_count=page_num)
    from dataclasses import replace
    from audit_ai.ocr.geometry import restore_regions
    return replace(restore_regions(best_page, image.size, rotations), page_num=page_num), best_scores
