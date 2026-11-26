"""Verify frozen synthetic fixtures locally, without contacting an application or model."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]


def inspect_materials(directory, rotation_fixture=None):
    manifest_path = directory / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("synthetic") is not True or manifest.get("frozenBeforeUpload") is not True:
        raise ValueError("Expected frozen synthetic fixtures")
    if len(manifest["batches"]) != 3 or {batch["id"] for batch in manifest["batches"]} != {"F26B44", "F26P24", "F26C24"}:
        raise ValueError("Unexpected batch inventory")
    records = []
    for batch in manifest["batches"]:
        folder = (directory / batch["folder"]).resolve()
        if folder.parent != directory.resolve():
            raise ValueError("Batch folder escapes fixture directory")
        names = batch["files"]
        if len(names) != 4 or len(set(names)) != 4 or set(batch["sha256"]) != set(names):
            raise ValueError("Expected four distinct hashed files per batch")
        if {item.name for item in folder.iterdir()} != set(names):
            raise ValueError("Batch directory differs from manifest")
        for name in names:
            path = (folder / name).resolve()
            if path.parent != folder:
                raise ValueError("Material escapes batch folder")
            data = path.read_bytes()
            digest = hashlib.sha256(data).hexdigest()
            if digest != batch["sha256"][name]:
                raise ValueError("Material hash mismatch: " + batch["id"] + "/" + name)
            record = dict(batch=batch["id"], file=name, bytes=len(data), sha256=digest)
            if path.suffix in {".docx", ".xlsx"}:
                with zipfile.ZipFile(path) as archive:
                    if archive.testzip() is not None:
                        raise ValueError("Invalid Office archive CRC")
                    for member in archive.namelist():
                        if member.endswith(".xml"):
                            ET.fromstring(archive.read(member))
                    if path.suffix == ".docx":
                        document = ET.fromstring(archive.read("word/document.xml"))
                        ns = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
                        record["paragraphs"] = len(document.findall(".//w:p", ns))
                    else:
                        ns = {"s": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
                        workbook = ET.fromstring(archive.read("xl/workbook.xml"))
                        record["sheets"] = [item.attrib["name"] for item in workbook.findall("s:sheets/s:sheet", ns)]
            elif path.suffix == ".pdf":
                import pypdfium2 as pdfium

                with pdfium.PdfDocument(path) as document:
                    record["pages"] = len(document)
                    record["nativeTextCharsByPage"] = []
                    for page_number in range(len(document)):
                        page = document[page_number]
                        try:
                            text_page = page.get_textpage()
                            try:
                                record["nativeTextCharsByPage"].append(text_page.count_chars())
                            finally:
                                text_page.close()
                            if rotation_fixture and batch["id"] == "F26C24" and name == "廉洁保密协议.pdf" and page_number == 0:
                                rotation_fixture.parent.mkdir(parents=True, exist_ok=True)
                                bitmap = page.render(scale=1)
                                try:
                                    bitmap.to_pil().save(rotation_fixture)
                                finally:
                                    bitmap.close()
                        finally:
                            page.close()
            else:
                raise ValueError("Unexpected material format")
            records.append(record)
    return dict(status="PASS", scope="LOCAL_FILE_INTEGRITY_AND_STRUCTURE",
                manifestSha256=hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
                files=records, remoteStatus="PENDING_LOCAL_26")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=ROOT / "test-materials/field-simulation-20261126-r3")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--rotation-fixture", type=Path)
    args = parser.parse_args()
    result = inspect_materials(args.directory, args.rotation_fixture)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(dict(status=result["status"], files=len(result["files"]), remoteStatus=result["remoteStatus"])))


if __name__ == "__main__":
    main()
