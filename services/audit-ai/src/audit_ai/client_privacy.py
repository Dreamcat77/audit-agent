"""Request-local client identity protection at the final provider boundary.

Names arrive only from the internal Java service, never from provider output.
Original material, evidence identities and checkpoints remain server-owned. Masks
preserve code-point and UTF-16 lengths; only complete masks are reversible.
"""
from __future__ import annotations

import base64
import json
import re
import unicodedata
from contextvars import ContextVar
from dataclasses import dataclass

HEADER = 'X-Audit-Client-Identity'
CURRENT: ContextVar['ClientIdentity | None'] = ContextVar('client_identity', default=None)


def normalized(value):
    return re.sub(r'\s+', '', unicodedata.normalize('NFKC', value))


def party(text, names):
    """Match complete party fields, not mentions elsewhere or organization substrings."""
    found = set()
    pattern = r'(甲方|乙方)(?:\s*[（(][^）)\n]{0,24}[）)])?\s*[:：]\s*([^\n\r|；;]{2,160})'
    wanted = {normalized(n) for n in names}
    for match in re.finditer(pattern, text):
        if normalized(match[2].strip()) in wanted:
            found.add('PARTY_A' if match[1] == '甲方' else 'PARTY_B')
    return 'AMBIGUOUS' if len(found) > 1 else next(iter(found), 'UNMATCHED')


@dataclass(frozen=True, repr=False)
class ClientIdentity:
    names: tuple[str, ...]
    parties: tuple[tuple[str, str], ...]

    @classmethod
    def from_header(cls, header, payload):
        if len(header) > 8000:
            raise ValueError('CLIENT_IDENTITY_INVALID')
        try:
            raw = json.loads(base64.b64decode(header, validate=True))
            names = raw['names']
            if set(raw) != {'version', 'names'} or raw['version'] != 1:
                raise ValueError()
            if not isinstance(names, list) or not 1 <= len(names) <= 64:
                raise ValueError()
            if any(not isinstance(n, str) or not 2 <= len(normalized(n)) <= 120 for n in names):
                raise ValueError()
            names = tuple(dict.fromkeys(names))
            parties = {}
            def visit(value):
                if isinstance(value, dict):
                    identity = value.get('id', value.get('fileId'))
                    if isinstance(identity, str) and isinstance(value.get('text'), str):
                        parties[identity] = party(value['text'], names)
                    for child in value.values(): visit(child)
                elif isinstance(value, list):
                    for child in value: visit(child)
            visit(payload)
            # Java resolves full-file roles before bounded fragments are selected.
            explicit = payload.get('reviewParties', {}) if isinstance(payload, dict) else {}
            if not isinstance(explicit, dict) or len(explicit) > 8:
                raise ValueError()
            for key, role in explicit.items():
                if role not in ('PARTY_A', 'PARTY_B', 'AMBIGUOUS', 'UNMATCHED'):
                    raise ValueError()
                parties[key] = role
            return cls(names, tuple(sorted(parties.items())))
        except (ValueError, KeyError, TypeError, UnicodeError):
            raise ValueError('CLIENT_IDENTITY_INVALID') from None

    def boundary(self, body):
        return ProviderBoundary(self, body)


class ProviderBoundary:
    def __init__(self, identity, body):
        self.identity = identity
        self.reverse = {}
        originals = json.dumps(body, ensure_ascii=False)
        self.used = originals
        aliases = sorted({normalized(n) for n in identity.names}, key=len, reverse=True)
        def character(c):
            variants={c}
            if 0x21 <= ord(c) <= 0x7E: variants.add(chr(ord(c)+0xFEE0))
            return '(?:'+'|'.join(re.escape(v) for v in sorted(variants))+')'
        self.pattern = re.compile('|'.join(r'\s*'.join(character(c) for c in n) for n in aliases))
        self.body = self._walk(body, self._mask)
        # Applicable to all calls, including retries and GROUP/FINAL synthesis.
        policy = ('\n审查对象保护与立场：受保护名称已使用等长代号替换，代号仅为原文实体引用，不是缺字或OCR错误。'
                  '不得推测、恢复或询问其真实名称；引用证据须逐字保留完整代号。'
                  '本系统只服务配置的客户；以下角色由服务端依据合同当事人确定，不能按甲方=采购人猜测。'
                  '对已确定的我方角色，以维护其合法利益为审查立场，分析对我方履约、付款、验收、救济、解约和持续使用的影响；'
                  '依据原文区分付款人和收款人、义务人和权利人；我方为乙方时也不能自动认定为供货人。'
                  '不替我方制造事实，不将不利商业条款一概认定违法，不删掉对我方有利的保护。'
                  'UNMATCHED不得擅自站在甲方立场，AMBIGUOUS不得自动选择一方，均只作中性核查并说明立场未确定。'
                  '各文件我方角色：' + json.dumps(dict(identity.parties), ensure_ascii=False))
        known = {role for _, role in identity.parties if role in ('PARTY_A', 'PARTY_B')}
        if len(known) == 1 and not any(role == 'AMBIGUOUS' for _, role in identity.parties):
            policy += '已明确匹配的合同中我方角色为'+next(iter(known))+'；不能把该角色外推给未匹配的其他合同。'
        self.body['messages'][0]['content'] += policy
        def readable(value):
            if isinstance(value, str):
                return re.sub(r'(?:\\u[0-9a-fA-F]{4})+', lambda m: json.loads('"'+m[0]+'"'), value)
            return ''
        strings = []
        def collect(value):
            if isinstance(value, str): strings.append(readable(value))
            elif isinstance(value, list):
                for item in value: collect(item)
            elif isinstance(value, dict):
                for key, item in value.items(): collect(key); collect(item)
        collect(self.body)
        if any(normalized(n) in normalized(s) for n in identity.names for s in strings):
            # Unsupported character normalization must fail before HTTP, never leak.
            raise ValueError('CLIENT_IDENTITY_REDACTION_UNPROVEN')

    def _mask(self, value):
        def replace(match):
            original = match[0]
            for token, source in self.reverse.items():
                if source == original: return token
            index = len(self.reverse) + 1
            while True:
                # Private-use characters avoid collisions with ordinary business text.
                token = ''.join(chr((0xF0000 if ord(c) > 0xFFFF else 0xE000) + index)
                                if not c.isspace() else c for c in original)
                if token not in self.used and token not in self.reverse: break
                index += 1
            self.reverse[token] = original
            return token
        return self.pattern.sub(replace, value)

    def _walk(self, value, transform):
        if isinstance(value, str):
            # Model user content can itself contain ASCII-escaped JSON.
            if value.lstrip().startswith(('{', '[')):
                try:
                    parsed = json.loads(value)
                    return json.dumps(self._walk(parsed, transform), ensure_ascii=False, separators=(',', ':'))
                except (ValueError, TypeError): pass
            return transform(value)
        if isinstance(value, list):return [self._walk(v, transform) for v in value]
        if isinstance(value, dict):return {transform(k): self._walk(v, transform) for k, v in value.items()}
        return value

    def restore(self, content):
        def unmask(value):
            for token, original in sorted(self.reverse.items(), key=lambda p: len(p[0]), reverse=True):
                value = value.replace(token, original)
            return value
        # Only restore the provider's business JSON, never hash/fingerprint metadata.
        return self._walk(content, unmask)
