"""Source-only party resolution, shared in behavior with ClientPartyMatcher.java.

No model guesses, edit-distance matching, cross-document alias learning or writes.
"""
from __future__ import annotations

import re
import unicodedata
from typing import Literal

ReviewParty = Literal['PARTY_A', 'PARTY_B', 'PURCHASER', 'PRINCIPAL', 'BUYER', 'SELLER',
                      'SUPPLIER', 'SERVICE_PROVIDER', 'EMPLOYER', 'CONTRACTOR', 'LESSOR',
                      'LESSEE', 'AMBIGUOUS', 'UNMATCHED']
ROLE_CODES = frozenset(ReviewParty.__args__)
_FUNCTIONAL = dict.fromkeys(('采购人', '采购单位', '采购方'), 'PURCHASER') | dict.fromkeys(
    ('委托人', '委托方', '委托单位'), 'PRINCIPAL') | dict.fromkeys(('买方', '需方'), 'BUYER') | {
    '卖方': 'SELLER', '供应商': 'SUPPLIER', '供方': 'SUPPLIER', '受托人': 'SERVICE_PROVIDER',
    '受托方': 'SERVICE_PROVIDER', '服务方': 'SERVICE_PROVIDER', '发包人': 'EMPLOYER',
    '发包方': 'EMPLOYER', '承包人': 'CONTRACTOR', '承包方': 'CONTRACTOR',
    '出租人': 'LESSOR', '出租方': 'LESSOR', '承租人': 'LESSEE', '承租方': 'LESSEE'}

LABELS = ('甲方名称', '乙方名称', '采购人名称', '采购单位名称', '委托单位名称',
          '甲方', '乙方', '采购人', '采购单位', '采购方', '买方', '需方', '委托人', '委托方', '委托单位',
          '发包人', '发包方', '出租人', '出租方', '承租人', '承租方', '供应商', '供方',
          '卖方', '受托人', '受托方', '承包人', '承包方', '服务方')
_LABEL = '|'.join(r'[^\S\r\n]*'.join(label) for label in LABELS)
_FIELD = re.compile(r'(?m)(?:^|[|｜;；])[^\S\r\n]*((' + _LABEL +
                    r')(?:[^\S\r\n]*[（(][^）)\r\n]{0,40}[）)])?)[^\S\r\n]*(?:[:：]|\r?\n|[^\S\r\n]{2,})'
                    r'[^\S\r\n]*([^\r\n|｜;；]{0,220})')
_ROLE = re.compile(r'甲方|乙方')
_ANNOTATION = re.compile(r'\((?:(?:以下)?(?:简称|称|统称|称为|作为))?[“”"\'‘’]*(甲方|乙方|采购人|采购方|委托人|委托方|买方|需方|盖章|签章|公章)[“”"\'‘’]*\)')
_SUFFIX_ORG = re.compile(r'(?:公司|企业|分局|支队|大队|派出所|委员会|事务所|合作社|医院|学校|学院)$')
_DEPARTMENT = re.compile(r'.{1,32}(?:处|科|办公室|中心)$')
_PARENT = re.compile(r'.{1,100}(?:局|厅|委员会)$')
_HEADING = re.compile(r'^(?:(?:附件|附录).{0,20})?(?:历史业绩|类似业绩|过往业绩|参考合同|历史合同)')
_ALIAS = re.compile(r'^(.*?)\((?:以下简称|以下称|简称|下称)[:：]?[“”"\'‘’]*([\w\u4e00-\u9fff]{2,20})[“”"\'‘’]*\)[，,。.]*$')


def normalize(value):
    return re.sub(r'[\s\u200b\ufeff]+', '', unicodedata.normalize('NFKC', value)).replace('巿', '市')


def variants(names):
    """Expand formatting and approved department abbreviations, never arbitrary suffixes."""
    result = {normalize(n) for n in names}
    for name in tuple(result):
        # Established administrative abbreviation, only when explicitly configured.
        if re.fullmatch(r'警保(?:处|科)', name):
            result.add(name.replace('警保', '警务保障'))
        if re.fullmatch(r'.{2,60}市公安局', name):
            result.add(name[:-4] + '公安局')
    parents = [n for n in result if _PARENT.fullmatch(n)]
    departments = [n for n in result if _DEPARTMENT.fullmatch(n) and not _PARENT.fullmatch(n)]
    for parent in parents:
        for department in departments:
            result.add(parent + department)
            result.add(parent + '(' + department + ')')
    return frozenset(result)


def entity(value, wanted):
    value = normalize(value).strip('，,。.“”"\'‘’')
    value = _ANNOTATION.sub('', value).strip('，,。.“”"\'‘’')
    value = re.sub(r'\((?:主管|主管单位)\)$', '', value)
    value = re.sub(r'\((?:以下简称|以下称|简称|下称)[:：]?[^()]{1,30}\)$', '', value).strip('，,。.“”"\'‘’')
    value = re.split(r'[,，](?:地址|住所|法定代表人|负责人|联系人|联系电话)[:：]', value, maxsplit=1)[0]
    if value.endswith('本级') and value[:-2] in wanted:
        value = value[:-2]
    return value in wanted


def _role(label, value):
    roles = set(_ROLE.findall(normalize(label)))
    # A functional label is not inherently PARTY_A: explicit binding is required.
    for annotation in re.findall(r'[（(]([^）)]{0,40})[）)]', value):
        if re.fullmatch(r'(?:以下)?(?:简称|称|统称|称为)?[“”"\'‘’]*(?:甲方|乙方)[“”"\'‘’]*', normalize(annotation)):
            roles.update(_ROLE.findall(annotation))
    return roles


def _fields(text):
    cutoff = len(text)
    offset = 0
    for line in text.splitlines(keepends=True):
        if _HEADING.match(normalize(line)):
            cutoff = offset
            break
        offset += len(line)
    for match in _FIELD.finditer(text):
        if match.start() >= cutoff:
            break
        yield match


def declared_aliases(text, wanted):
    declarations = {}
    for match in _fields(text):
        declaration = _ALIAS.fullmatch(normalize(match[3]))
        if declaration and declaration[2] not in LABELS and declaration[2] not in {'双方', '我方', '对方', '客户'}:
            declarations.setdefault(declaration[2], set()).add(entity(declaration[1], wanted))
    # A conflicting definition cannot become a new trusted alias.
    return {alias for alias, known in declarations.items() if known == {True}}


def party(text, names):
    wanted = variants(names)
    wanted = wanted | declared_aliases(text, wanted)
    found = set()
    functional_found = set()
    bound = {}
    fields = []
    # Read current party definitions; later historical appendices cannot flip the role.
    for match in _fields(text):
        label, functional, value = match[1], normalize(match[2]).removesuffix('名称'), match[3]
        roles = _role(label, value)
        if roles:
            bound.setdefault(functional, set()).update(roles)
        fields.append((functional, value, roles, match.end()))
    for functional, value, roles, end in fields:
        roles = roles or bound.get(functional, set())
        tail = text[end:end + 240].splitlines()
        # OCR may wrap an organization over several lines. Stop at other labels.
        continuations = []
        for line in tail[1:3] if tail and not tail[0] else tail[:2]:
            line = line.strip()
            if not line or re.search(r'[:：|；;。]', line) or len(normalize(line)) > 50:
                break
            continuations.append(line)
        candidate = value
        matched = entity(candidate, wanted)
        if matched and continuations and _SUFFIX_ORG.search(normalize(continuations[0])):
            matched = False  # A wrapped company suffix changes the party identity.
        if not matched:
            for line in continuations:
                candidate += line
                if entity(candidate, wanted):
                    matched = True
                    break
        if matched:
            found.update('PARTY_A' if role == '甲方' else 'PARTY_B' for role in roles)
            if functional in _FUNCTIONAL:
                functional_found.add(_FUNCTIONAL[functional])
    result = found or functional_found
    return 'AMBIGUOUS' if len(result) > 1 else next(iter(result), 'UNMATCHED')
