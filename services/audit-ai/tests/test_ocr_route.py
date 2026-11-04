from pathlib import Path
import sys

from fastapi.testclient import TestClient

from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.ocr.port import OcrExtraction, OcrPage, OcrPort


class RecordingOcrPort(OcrPort):
    def __init__(self) -> None:
        self.paths: list[Path] = []
        self.payloads: list[bytes] = []

    def extract(self, path: Path) -> OcrExtraction:
        self.paths.append(path)
        self.payloads.append(path.read_bytes())
        return OcrExtraction(
            text="施工合同\n工期约定",
            confidence=0.95,
            pages=(OcrPage(page_num=1, text="施工合同\n工期约定", confidence=0.95),),
            engine="PADDLEOCR_3.7.0",
            duration_ms=12,
        )


def local_settings(**overrides: object) -> Settings:
    temp_root = Path(".test-runtime/ocr")
    temp_root.mkdir(parents=True, exist_ok=True)
    return Settings(ocr_temp_root=str(temp_root), **overrides)


def test_importing_application_does_not_import_paddle_modules() -> None:
    assert "paddle" not in sys.modules
    assert "paddleocr" not in sys.modules


def test_ocr_route_streams_to_temp_file_and_returns_java_contract() -> None:
    port = RecordingOcrPort()
    client = TestClient(create_app(settings=local_settings(), ocr_port=port))

    response = client.post(
        "/ai/v1/ocr/extract",
        files={"file": ("scan.png", b"\x89PNG\r\n\x1a\nbody", "image/png")},
    )

    assert response.status_code == 200
    assert response.json() == {
        "code": 200,
        "message": "success",
        "data": {
            "text": "施工合同\n工期约定",
            "confidence": 0.95,
            "pages": [
                {"pageNum": 1, "text": "施工合同\n工期约定", "confidence": 0.95}
            ],
            "engine": "PADDLEOCR_3.7.0",
            "durationMs": 12,
        },
    }
    assert port.payloads == [b"\x89PNG\r\n\x1a\nbody"]
    assert port.paths[0].suffix == ".png"
    assert not port.paths[0].exists()


def test_ocr_route_rejects_oversized_upload_without_calling_port() -> None:
    port = RecordingOcrPort()
    settings = local_settings(max_upload_bytes=5)
    client = TestClient(create_app(settings=settings, ocr_port=port))

    response = client.post(
        "/ai/v1/ocr/extract",
        files={"file": ("scan.jpg", b"123456", "image/jpeg")},
    )

    assert response.status_code == 413
    assert response.json()["code"] == "UPLOAD_TOO_LARGE"
    assert port.paths == []


def test_ocr_route_rejects_unsupported_media_without_calling_port() -> None:
    port = RecordingOcrPort()
    client = TestClient(create_app(settings=local_settings(), ocr_port=port))

    response = client.post(
        "/ai/v1/ocr/extract",
        files={"file": ("notes.txt", b"plain text", "text/plain")},
    )

    assert response.status_code == 415
    assert response.json()["code"] == "OCR_UNSUPPORTED_MEDIA_TYPE"
    assert port.paths == []
