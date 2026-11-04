"""Offline gpt2/qwen35 GGUF byte BPE counter, NOT a runtime-tokenizer claim.

Primary reference: ggml-org/llama.cpp src/unicode.cpp, custom_qwen35.
Counts content only. Chat renderer/control tokens require an independent
bound/calibration against actual provider prompt usage. No network or I/O.
Python Unicode categories may differ from a runner's compiled Unicode table;
therefore calibration/coverage results must accompany any use of this helper.
"""
from functools import lru_cache
import re
import unicodedata

_SPACE = frozenset(chr(c) for c in [9, 10, 11, 12, 13, 32, 133, 160, 5760,
    *range(8192, 8203), 8232, 8233, 8239, 8287, 12288])


def pretokenize(text):
    def kind(i): return unicodedata.category(text[i])[0] if i < len(text) else ""
    def at(i): return text[i] if i < len(text) else ""
    parts, pos = [], 0
    while pos < len(text):
        start, c = pos, text[pos]
        if c == "'":
            match = re.match(r"(?i:'s|'t|'re|'ve|'m|'ll|'d)", text[pos:])
            if match:
                pos += len(match.group()); parts.append(text[start:pos]); continue
        if c not in "\r\n" and kind(pos) != "N" and (kind(pos) in ("L", "M") or kind(pos+1) in ("L", "M")):
            pos += 1
            while kind(pos) in ("L", "M"): pos += 1
        elif kind(pos) == "N":
            pos += 1
        elif (at(pos+1) if c == " " else c) not in _SPACE and kind(pos+1 if c == " " else pos) not in ("", "L", "M", "N"):
            pos += int(c == " ")
            while at(pos) and at(pos) not in _SPACE and kind(pos) not in ("L", "M", "N"): pos += 1
            while at(pos) and at(pos) in "\r\n": pos += 1
        else:
            end, last_newline = pos, None
            while at(end) in _SPACE:
                if at(end) in "\r\n": last_newline = end + 1
                end += 1
            if last_newline is not None: pos = last_newline
            elif end - pos > 1 and end < len(text): pos = end - 1
            elif end > pos: pos = end
            else: pos += 1
        parts.append(text[start:pos])
    return parts


def _byte_encoder():
    original = [*range(33, 127), *range(161, 173), *range(174, 256)]
    result = {b: chr(b) for b in original}
    for b in range(256):
        if b not in result: result[b] = chr(256 + sum(1 for x in range(b) if x not in original))
    return result


class TokenCounter:
    def __init__(self, metadata):
        if metadata.get("tokenizer.ggml.model") != "gpt2" or metadata.get("tokenizer.ggml.pre") != "qwen35":
            raise ValueError("TOKENIZER_KIND_UNSUPPORTED")
        self.ranks = {tuple(value.split(" ", 1)): index for index, value in enumerate(metadata["tokenizer.ggml.merges"])}
        self.encoder = _byte_encoder()
        tokens, types = metadata.get("tokenizer.ggml.tokens", []), metadata.get("tokenizer.ggml.token_type", [])
        special = [token for token, kind in zip(tokens, types) if kind in (3, 4)]
        self.special = re.compile("|".join(re.escape(t) for t in sorted(special, key=len, reverse=True))) if special else None

    @lru_cache(maxsize=32768)
    def _piece(self, value):
        symbols = [self.encoder[b] for b in value.encode("utf-8", errors="strict")]
        while len(symbols) > 1:
            candidates = [(self.ranks[pair], i) for i, pair in enumerate(zip(symbols, symbols[1:])) if pair in self.ranks]
            if not candidates: break
            _, i = min(candidates)
            symbols[i:i+2] = [symbols[i] + symbols[i+1]]
        return len(symbols)

    def _ordinary(self, text): return sum(self._piece(part) for part in pretokenize(text))

    def count(self, text):
        if not isinstance(text, str): raise ValueError("TOKENIZER_TEXT_REQUIRED")
        if self.special is None: return self._ordinary(text)
        total, start = 0, 0
        for match in self.special.finditer(text):
            total += self._ordinary(text[start:match.start()]) + 1
            start = match.end()
        return total + self._ordinary(text[start:])

# Same reference BPE implementation as the existing locally verified helper.
# Not a new tokenizer/renderer claim; preserve the approved 2048-token reserve.
import hashlib,json,os
from pathlib import Path

PROTOCOL='contract-business-reference-context-budget-v1'

@lru_cache(maxsize=2)
def _load(path,expected):
    from audit_ai.model.gateway import ModelGatewayFailure
    try:
        raw=Path(path).read_bytes()
        if len(raw)>67108864 or not re.fullmatch('[a-f0-9]{64}',expected or '') or hashlib.sha256(raw).hexdigest()!=expected:
            raise ValueError()
        metadata=json.loads(raw)
        return TokenCounter(metadata.get('metadata',metadata))
    except (OSError,ValueError,TypeError,KeyError):
        raise ModelGatewayFailure(reason='BUSINESS_CONTEXT_ADMISSION_UNPROVEN',retryable=False,duration_ms=0) from None

def admit(system,user,*,counter=None):
    from audit_ai.model.gateway import ModelGatewayFailure
    if counter is None:
        path=os.getenv('AUDIT_CONTRACT_BUSINESS_TOKENIZER_METADATA')
        expected=os.getenv('AUDIT_CONTRACT_BUSINESS_TOKENIZER_SHA256')
        if not path or not expected:
            raise ModelGatewayFailure(reason='BUSINESS_CONTEXT_ADMISSION_UNPROVEN',retryable=False,duration_ms=0)
        counter=_load(path,expected)
    tokens=counter.count(system)+counter.count(user)
    if tokens+6144+2048>32768:
        raise ModelGatewayFailure(reason='INPUT_CONTEXT_BUDGET_EXCEEDED',retryable=False,duration_ms=0)
    return {'protocol':PROTOCOL,'inputReferenceBpe':tokens,'estimatedTotalWithReservations':tokens+8192,
        'outputReservation':6144,'rendererAndSafetyReservation':2048,'context':32768,
        'referenceEstimateNotExactRunnerCount':True}
