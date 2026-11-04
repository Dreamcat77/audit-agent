"""Real CPU OCR benchmark of hash-verified, explicitly synthetic local fixtures only."""
from __future__ import annotations
import argparse
import hashlib
import inspect
import json
import math
import platform
from pathlib import Path
import time

from audit_ai.config import Settings
from audit_ai.ocr.paddle_adapter import PaddleOcrAdapter

ROOT=Path(__file__).resolve().parents[2]
FIXTURES=ROOT/".runtime/contract-repair-input-20261014/synthetic_contract_regression"
OUTPUT=ROOT/".runtime/contract-repair-test/ocr-benchmark"

def main() -> None:
    parser=argparse.ArgumentParser()
    parser.add_argument("--slow",action="store_true")
    options=parser.parse_args()
    manifest=json.loads((FIXTURES/"manifest.json").read_text(encoding="utf-8"))
    if not manifest.get("synthetic") or not manifest.get("no_real_customer_data"):
        raise RuntimeError("SYNTHETIC_MANIFEST_REQUIRED")
    entry=next((name,item) for name,item in manifest["files"].items() if name.endswith("_01_8页.pdf"))
    source=FIXTURES/entry[0]
    if hashlib.sha256(source.read_bytes()).hexdigest()!=entry[1]["sha256"]:
        raise RuntimeError("SYNTHETIC_FIXTURE_SHA_MISMATCH")
    OUTPUT.mkdir(parents=True,exist_ok=True)
    adapter=PaddleOcrAdapter(Settings())
    records=[]
    def measure(path:Path,label:str) -> dict:
        import pypdfium2 as pdfium
        with pdfium.PdfDocument(path) as document:
            page_count=len(document)
            from paddlex.utils.flags import PDF_RENDER_SCALE
            page=document[0]
            dimensions=[math.ceil(value*PDF_RENDER_SCALE) for value in page.get_size()]
            page.close()
        print("OCR_BENCHMARK_STARTED "+label+" pages="+str(page_count),flush=True)
        before=time.perf_counter_ns()
        result=adapter.extract(path)
        elapsed=(time.perf_counter_ns()-before)//1_000_000
        if len(result.pages)!=page_count or [p.page_num for p in result.pages]!=list(range(1,page_count+1)):
            raise RuntimeError("OCR_COMPLETE_PAGE_COVERAGE_UNPROVEN")
        if not all(p.text.strip() for p in result.pages):
            raise RuntimeError("SYNTHETIC_OCR_EMPTY_PAGE")
        record={"sample":label,"pages":page_count,"bytes":path.stat().st_size,
                "fileSha256":hashlib.sha256(path.read_bytes()).hexdigest(),"wallMs":elapsed,"ocrMs":result.duration_ms,
                "engine":result.engine,"cpuThreads":1,"mkldnn":False,"concurrency":1,
                "renderScale":PDF_RENDER_SCALE,"renderDpi":PDF_RENDER_SCALE*72,"renderDimensions":dimensions,
                "python":platform.python_version(),"textSha256":hashlib.sha256(result.text.encode()).hexdigest(),
                "textCodepoints":len(result.text),"predictIsGeneratorFunction":inspect.isgeneratorfunction(adapter._pipeline.predict)}
        records.append(record)
        (OUTPUT/"metrics.json").write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding="utf-8")
        print("OCR_BENCHMARK_RESULT "+json.dumps(record,ensure_ascii=False),flush=True)
        return record
    baseline=measure(source,"verified-eight-page")
    if options.slow and baseline["ocrMs"]>=120000:
        print("OCR_120S_WORKLOAD=PASS",flush=True)
    elif options.slow:
        # Grow actual image-page workload; never simulate inference time with sleeps.
        copies=max(2,min(4,math.ceil(150000/max(1,baseline["ocrMs"]))))
        import pypdfium2 as pdfium
        slow=OUTPUT/("synthetic-slow-"+str(copies*8)+"-pages.pdf")
        with pdfium.PdfDocument(source) as original,pdfium.PdfDocument.new() as document:
            for _ in range(copies):document.import_pages(original)
            document.save(slow)
        slow_record=measure(slow,"real-slow-scan")
        print("OCR_120S_WORKLOAD="+("PASS" if slow_record["ocrMs"]>=120000 else "NOT_REACHED"),flush=True)

if __name__=="__main__":
    main()
