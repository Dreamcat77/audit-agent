"""Add a structurally damaged synthetic OOXML file without weakening upload policy."""
import json
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
from docx import Document

root = Path(__file__).resolve().parents[2] / ".test-runtime/contract-fixtures"
specs = json.loads((root / "golden.json").read_text(encoding="utf-8"))
spec = next(s for s in specs if s["id"] == "partial-file")
path = root / "partial-file/内部损坏附件.docx"
buffer = BytesIO()
Document().save(buffer)
with ZipFile(BytesIO(buffer.getvalue())) as template, ZipFile(path, "w", ZIP_DEFLATED) as damaged:
    for entry in template.infolist():
        damaged.writestr(entry.filename, b"<invalid-unclosed" if entry.filename == "word/document.xml" else template.read(entry.filename))
spec["paths"] = [spec["paths"][0], str(path)]
(root / "golden.json").write_text(json.dumps(specs, ensure_ascii=False, indent=2), encoding="utf-8")
print("PARSER_FAILURE_FIXTURE_READY original_signature_rejection_fixture_preserved=true")
