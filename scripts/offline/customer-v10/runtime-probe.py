"""Offline import and syntax probe. Never reads model gateway configuration."""
import ast
import importlib
from pathlib import Path
import sys

try:
    if sys.version_info[:2] != (3, 11):
        raise RuntimeError('PYTHON_VERSION')
    for name in ('fastapi','uvicorn','httpx','pydantic','multipart','PIL','numpy','cv2','pypdfium2','paddle','paddleocr'):
        importlib.import_module(name)
    import paddleocr
    if not str(paddleocr.__version__).startswith('3.7.'):
        raise RuntimeError('PADDLEOCR_VERSION')
    for source in Path(sys.argv[1]).rglob('*.py'):
        ast.parse(source.read_text(encoding='utf-8'))
except Exception as failure:
    # Dependency errors may include private paths; only report the safe class.
    print('RUNTIME_PROBE=FAIL reasonCode=EXISTING_DEPENDENCY_OR_SOURCE type='+type(failure).__name__)
    raise SystemExit(1)
print('PYTHON311_PADDLEOCR37_SOURCE_IMPORTS=PASS')
