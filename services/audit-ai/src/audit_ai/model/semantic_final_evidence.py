"""Versioned relevant FINAL source view. No dispatch, persistence or inference.

The trusted catalog must be rebuilt from immutable request materials by the
caller. View self-consistency is not accepted as source authority. Redacted
source offsets use Unicode code points; masked spans never become citations.
"""
from copy import deepcopy
from collections import Counter
import hashlib
import json
import re

from audit_ai.errors import AuditAiError

HIERARCHY_PROTOCOL = "contract-bounded-hierarchy-v2"
PROVIDER_PROTOCOL = "contract-hierarchy-provider-references-v2"
PROTOCOL = "contract-final-frozen-evidence-v1"
MAX_CHARS = 48_000
MAX_BYTES = 144_000
TEXT_ENCODING = "literal-lines-v1"
SELECTION_PROTOCOL = "same-file-clause-candidates-v1"

# Location leads, not entailment rules. Never conclude absence from a miss.
_TOPICS = {
    "STRUCTURE": r"附件|组成|份数|效力",
    "PROJECT_IDENTITY": r"项目名称|项目编号|合同编号|采购编号|标的名称",
    "SUPPLIER_IDENTITY": r"(?:甲方|乙方|采购人|供应商|承包人)\s*[:：]|信用代码|法定代表|注册地址|营业执照|资质",
    "DELIVERY": r"交付|交货|交接|进度|延期|逾期",
    "ACCEPTANCE": r"验收|不合格|整改|缺陷",
    "SERVICE_RESPONSE": r"响应时间|响应时限|故障|到场|报修",
    "WARRANTY": r"质保|保修|维护期|维护期限",
    "DELAY_LIABILITY": r"逾期|延期|迟延|延迟|未按.{0,30}(?:交付|交货)|未.{0,8}(?:按期|如期).{0,8}(?:交付|交货)",
    "DEFECT_LIABILITY": r"缺陷|不合格|违约|赔偿",
    "OTHER_LIABILITY": r"违约|赔偿|责任|扣减",
    "SIGNING": r"签订|签字|签章|盖章",
    "EFFECTIVENESS": r"生效|效力|签章|盖章",
    "PERFORMANCE_PERIOD": r"履约期|合同期|服务期|有效期|截止|起止",
    "TERMINATION": r"解除|终止|生效|效力",
    "SUBCONTRACTING": r"分包|转包|(?:范围|内容).{0,12}(?:调整|变更)|(?:调整|变更).{0,12}(?:范围|内容)|变更程序",
}
_POSITIVE_TOPICS={**_TOPICS,
    'DELIVERY':r'(?:交付|交货)(?:时间|期限|日期|进度|方式|地点|安排)|(?:服务|交付|交货)期(?:限|间)|成果(?:交接|交付)',
    'ACCEPTANCE':r'验收(?:标准|方式|条件|程序|期限|流程)|(?:组织|通过|完成)验收',
    'STRUCTURE':r'附件|合同组成|文本份数|一式|组成部分',
    'OTHER':r'服务范围[^\n]{0,6}[:：]|技术范围[^\n]{0,6}[:：]|乙方.{0,6}提供|合同组成|一式',
}


def _selection_inputs(bundle, catalog):
    return [{"leafId": leaf, "topic": item["hint"]["obligationScope"]["topic"],
             "anchors": list(dict.fromkeys([*item["hint"]["problemEvidenceIds"],
                                             *item["hint"]["comparisonEvidenceIds"]])),
             "problemAnchors": item['hint']['problemEvidenceIds'],
             "seekCounterevidence": item['hint']['attention'] != 'INFO',
             "criticalLiterals": re.findall(r'\d{4}[-年/]\d{1,2}[-月/]\d{1,2}日?|\d+(?:\.\d+)?[%％]|\b[A-Z]+[-－]\d+\b',
                 item['hint']['title']+' '+item['hint']['description']),
             "privateWitnessOnly": item['hint']['claimKind'] == 'LOCAL_NOT_SEEN' and not item['hint']['problemEvidenceIds']}
            for leaf, item in bundle["leaves"].items()]


def _clause_spans(text):
    # Blank lines are not semantic boundaries: an exception can follow either
    # one or several line breaks. Without a recognizable next clause, retain
    # the entire remaining unit; capacity must fail rather than drop its tail.
    heading=r'(?m)^[^\S\r\n]*(?:\d+(?:\.\d+)+(?!\d)|\d+[.．、][^\S\r\n]+|第[一二三四五六七八九十百\d]+[条章节]|[一二三四五六七八九十]+、)'
    starts=sorted({0,*[m.start() for m in re.finditer(heading,text)]})
    return list(zip(starts,[*starts[1:],len(text)]))


def _select(inputs, catalog):
    selected, selections, visible_ranges = set(), [], {}
    file_sources = {fid: "".join(s.exact_text for s in catalog.segments if s.file_id == fid)
                    for fid in catalog.source_lengths}
    for item in inputs:
        anchors = item["anchors"]
        if (not set(anchors).issubset(catalog.by_id) or len(anchors) != len(set(anchors))
                or any(not catalog.by_id[e].citable for e in anchors)): _fail()
        files = {catalog.by_id[e].file_id for e in anchors}
        positive=not item.get('seekCounterevidence')
        pattern = (_POSITIVE_TOPICS if positive else _TOPICS).get(item["topic"])
        if item.get('criticalLiterals'):
            pattern='(?:'+(pattern or r'(?!)')+')|'+ '|'.join(re.escape(t) for t in item['criticalLiterals'])
        intervals, hits = [], []
        for fid in files:
            text = file_sources[fid]
            for start,end in _clause_spans(text):
                matches = list(re.finditer(pattern,text[start:end])) if pattern else []
                if (matches and (item.get('seekCounterevidence') or any(
                        catalog.by_id[e].file_id==fid and catalog.by_id[e].source_start < end
                        and catalog.by_id[e].source_end > start for e in anchors))):
                    intervals.append((fid,start,end))
                    hits.extend((fid,start+h.start(),start+h.end()) for h in matches)
        candidates = [s.evidence_id for s in catalog.segments if s.file_id in files and s.citable
            and any(s.file_id == fid and s.source_start < end and s.source_end > start for fid,start,end in hits)
            and (item.get('privateWitnessOnly') or s.evidence_id not in anchors)]
        # A local scope witness is not a problem anchor. Its original identity
        # remains in every leaf/checkpoint but does not force unrelated block
        # text into FINAL. All real risk and normal finding anchors stay visible.
        # Already-positive prose need not force every broad old comparison
        # witness back into FINAL. Keep all claims privately/on the group wire;
        # its original alias is publishable only when every cited ID is visible.
        # Otherwise FINAL must produce a separately validated supported N target.
        required_anchors=set(anchors) if not positive and not item.get('privateWitnessOnly') else set()
        if positive:
            candidates=[s.evidence_id for s in catalog.segments if s.file_id in files and s.citable
                and any(s.file_id==fid and s.source_start<end and s.source_end>start for fid,start,end in hits)]
            for eid in anchors:
                if len(_clause_spans(file_sources[catalog.by_id[eid].file_id]))==1:required_anchors.add(eid)
        base = required_anchors | set(candidates)
        # Keep whole original segments and complete boundary paragraphs. In
        # particular masked numerical splits cannot separate a rule's exception.
        context = set(base)
        for eid in base:
            s = catalog.by_id[eid]; text = file_sources[s.file_id]
            matched = [(start,end) for fid,start,end in intervals if fid==s.file_id
                       and s.source_start < end and s.source_end > start]
            # Unlocatable original evidence stays whole, never disappears.
            # Known problem anchors are separately retained whole below.
            if eid in item.get('problemAnchors', ()) or not matched:
                matched = [(start,end) for start,end in _clause_spans(text)
                           if start < s.source_end and end > s.source_start]
            for start,end in matched:
                for other in catalog.segments:
                    if other.file_id == s.file_id and other.source_start < end and other.source_end > start:
                        lo,hi=max(start,other.source_start),min(end,other.source_end)
                        if not other.exact_text[lo-other.source_start:hi-other.source_start].strip(): continue
                        context.add(other.evidence_id)
                        visible_ranges.setdefault(other.evidence_id, []).append([lo,hi])
        selected.update(context)
        selections.append({"leafId": item["leafId"], "anchors": anchors, "candidates": candidates,
            "boundaryContext": [s.evidence_id for s in catalog.segments if s.evidence_id in context - base],
            "searchMissProvesAbsence": False})
    for eid,ranges in visible_ranges.items():
        merged = []
        for start,end in sorted(ranges):
            if merged and start <= merged[-1][1]: merged[-1][1] = max(end,merged[-1][1])
            else: merged.append([start,end])
        visible_ranges[eid] = merged
    return selected, selections, visible_ranges


def _view(ids, files, catalog, coverage, selections, ranges, registry):
    indexes = {eid: i for i, eid in enumerate(registry)}
    sets, rows = [], []
    def intern(eids):
        value = [indexes[e] for e in eids]
        if value not in sets: sets.append(value)
        return sets.index(value)
    for row in selections:
        rows.append([row['leafId'], intern([e for e in row['anchors'] if e in ids]),
                     intern(row['candidates']), intern(row['boundaryContext'])])
    return {"protocol": PROTOCOL, "files": files, "evidenceRegistry": registry,
        "fullyVisibleEvidenceIds": [e for e in ids if ranges[e] == [[catalog.by_id[e].source_start,catalog.by_id[e].source_end]]],
        "evidenceColumns": ["evidenceId", "fileIndex", "sourceStart", "sourceEnd", "citable", "exactText"],
        "evidence": [[e, files.index([catalog.by_id[e].file_id, catalog.by_id[e].material_category]),
            start, end, catalog.by_id[e].citable,
            catalog.by_id[e].exact_text[start-catalog.by_id[e].source_start:end-catalog.by_id[e].source_start]]
            for e in ids for start,end in ranges[e]],
        "coverageColumns": ["fileId", "materialCategory", "sourceLength", "textSha256"],
        "coverage": coverage, "coverageMeaning": "FROZEN_CATALOG_NOT_MODEL_READ_COVERAGE",
        "selectionProtocol": SELECTION_PROTOCOL, "selectionColumns": ["leafId", "anchorsSet", "candidatesSet", "boundaryContextSet"],
        "selectionSets": sets, "selection": rows, "searchMissProvesAbsence": False}


def pack_view(view):
    """Factor repeated literal lines only; never summarize or shorten a value."""
    from audit_ai.model.semantic_hierarchy import canonical
    if view.get("evidenceColumns", [])[-1:] != ["exactText"]: _fail()
    lines = [row[-1].splitlines(keepends=True) for row in view["evidence"]]
    counts = Counter(line for group in lines for line in group)
    pool = list(dict.fromkeys(line for group in lines for line in group
                             if counts[line] > 1 and len(line.encode("utf-8")) >= 32))
    if not pool: return deepcopy(view)
    indexes = {text: i for i, text in enumerate(pool)}
    rows = []
    for row, group in zip(view["evidence"], lines, strict=True):
        parts = []
        for text in group:
            if text in indexes: parts.append(indexes[text])
            elif parts and type(parts[-1]) is str: parts[-1] += text
            else: parts.append(text)
        rows.append([*row[:-1], parts])
    packed = {**deepcopy(view), "evidenceTextEncoding": TEXT_ENCODING, "sourceTextPool": pool,
              "evidenceColumns": [*view["evidenceColumns"][:-1], "exactTextParts"], "evidence": rows}
    return packed if len(canonical(packed).encode("utf-8")) < len(canonical(view).encode("utf-8")) else deepcopy(view)


def unpack_view(view):
    """Strict independent-readable wire inverse, not source identity authority."""
    if "evidenceTextEncoding" not in view:
        if view.get("evidenceColumns", [])[-1:] != ["exactText"] or "sourceTextPool" in view: _fail()
        return deepcopy(view)
    pool = view.get("sourceTextPool")
    if (view.get("evidenceTextEncoding") != TEXT_ENCODING
            or view.get("evidenceColumns", [])[-1:] != ["exactTextParts"]
            or type(pool) is not list or not pool or any(type(t) is not str or not t for t in pool)
            or len(pool) != len(set(pool))): _fail()
    rows, used = [], set()
    for row in view.get("evidence", []):
        if type(row) is not list or len(row) != 6 or type(row[-1]) is not list or not row[-1]: _fail()
        parts = []
        for part in row[-1]:
            if type(part) is int and 0 <= part < len(pool): parts.append(pool[part]); used.add(part)
            elif type(part) is str and part: parts.append(part)
            else: _fail()
        rows.append([*row[:-1], "".join(parts)])
    if used != set(range(len(pool))): _fail()
    result = {**deepcopy(view), "evidenceColumns": [*view["evidenceColumns"][:-1], "exactText"], "evidence": rows}
    result.pop("sourceTextPool"); result.pop("evidenceTextEncoding")
    return result


def _fail(code="SEMANTIC_FINAL_EVIDENCE_IDENTITY", status=409):
    raise AuditAiError(status, code, "frozen final evidence cannot be verified")


def catalog_rows(catalog):
    return [{"evidenceId": s.evidence_id, "fileId": s.file_id, "materialCategory": s.material_category,
             "sourceStart": s.source_start, "sourceEnd": s.source_end, "citable": s.citable,
             "exactText": s.exact_text} for s in catalog.segments]


def enrich_bundle(bundle, rows, catalog):
    from audit_ai.model.semantic_hierarchy import canonical, fingerprint
    if catalog is None or rows != catalog_rows(catalog):
        _fail()
    lengths = dict(catalog.source_lengths)
    if not lengths or set(lengths) != {s.file_id for s in catalog.segments}:
        _fail()
    coverage = []
    for file_id, length in lengths.items():
        source = [s for s in catalog.segments if s.file_id == file_id]
        cursor, text = 0, []
        categories = {s.material_category for s in source}
        if len(categories) != 1:
            _fail()
        for s in source:
            if (s.source_start != cursor or s.source_end <= cursor
                    or len(s.exact_text) != s.source_end - s.source_start or type(s.citable) is not bool):
                _fail()
            cursor = s.source_end
            text.append(s.exact_text)
        if cursor != length:
            _fail()
        coverage.append([file_id, next(iter(categories)), length,
                         hashlib.sha256("".join(text).encode("utf-8")).hexdigest()])
    value = deepcopy(bundle)
    old_ids = value["evidenceIds"]
    inputs = _selection_inputs(bundle, catalog)
    selected, selections, ranges = _select(inputs, catalog)
    registry = [*old_ids, *(s.evidence_id for s in catalog.segments if s.evidence_id in selected - set(old_ids))]
    ids = [e for e in registry if e in selected]
    files = deepcopy(value["finalShell"]["files"])
    for s in catalog.segments:
        file = [s.file_id, s.material_category]
        if file not in files: files.append(file)
    evidence = []
    for eid in ids:
        s = catalog.by_id[eid]
        file = [s.file_id, s.material_category]
        if file not in files:
            files.append(file)
        evidence.append([eid, files.index(file), s.source_start, s.source_end, s.citable, s.exact_text])
    view = _view(ids, files, catalog, coverage, selections, ranges, registry)
    # First view hash, then grouping identity: never a circular proof.
    proof = {"protocol": PROTOCOL, "viewSha256": fingerprint(view), "complete": True,
             "fileCount": len(coverage), "segmentCount": len(catalog.segments), "visibleSegmentCount": len(ids),
             "evidenceOrder": ids, "evidenceRegistry": registry, "fileRegistry": files,
             "citableEvidenceIds": [s.evidence_id for s in catalog.segments if s.citable and s.evidence_id in selected],
             "sourceCoverage": coverage, "selectionInputs": inputs,
             "selectionProtocol": SELECTION_PROTOCOL, "selection": selections, "visibleRanges": ranges}
    packed_view = pack_view(view)
    if unpack_view(packed_view) != view: _fail()
    proof.update(textEncoding=TEXT_ENCODING, wireViewSha256=fingerprint(packed_view))
    grouping = fingerprint({"hierarchyProtocol": HIERARCHY_PROTOCOL,
                            "legacyGroupingFingerprint": bundle["groupingFingerprint"], "finalEvidenceProof": proof})
    shell = {**value["finalShell"], **packed_view, "evidenceRegistry": registry,
             "protocol": None, "hierarchyProtocol": HIERARCHY_PROTOCOL,
             "groupingFingerprint": grouping, "finalEvidenceProtocol": PROTOCOL, "finalEvidenceSha256": proof["viewSha256"]}
    shell.pop("protocol")
    # Digest budgets stay exactly the v1 allocations; raw evidence is additive.
    fixed = len(canonical(shell)) - 4 * value["groupCount"]
    fixed_bytes = len(canonical(shell).encode("utf-8")) - 4 * value["groupCount"]
    # FINAL can carry one explicit inheritance candidate per output group hint.
    # Reserve its full worst-case list now, not after all groups have run.
    worst_aliases=[f'G{gi+1}:H{hi+1}' for gi,g in enumerate(value['groups'])
                   for hi in range(min(100,len(g['leafIds'])))]
    eligibility_reserve=len(canonical({'inheritanceEligibility':{
        'protocol':'contract-final-inheritance-eligibility-v1','directTargetRefs':worst_aliases,
        'otherObservationsStillRequireDisposition':True,'notAClaimValidationProof':True}}))-1
    fixed+=eligibility_reserve;fixed_bytes+=eligibility_reserve
    digest_max = sum(g["digestMaxChars"] for g in value["groups"])
    if fixed + digest_max > MAX_CHARS or fixed_bytes + 4 * digest_max > MAX_BYTES:
        raise AuditAiError(413, "SEMANTIC_FINAL_EVIDENCE_BOUND", "frozen final evidence exceeds bound",
            {"visibleSegments": len(ids), "fixedChars": fixed, "digestMaxChars": digest_max,
             "worstCaseBytes": fixed_bytes + 4 * digest_max})
    for group in value["groups"]:
        source = json.loads(group["source"])
        source.update(hierarchyProtocol=HIERARCHY_PROTOCOL, groupingFingerprint=grouping)
        group["source"] = canonical(source)
        if len(group["source"]) > 12000:
            _fail("SEMANTIC_HIERARCHY_GROUP_INPUT_BOUND", 413)
    value.update(protocol=HIERARCHY_PROTOCOL, groupingFingerprint=grouping, finalShell=shell,
                 evidenceIds=registry, visibleEvidenceIds=ids, finalFixedChars=fixed, finalFixedBytes=fixed_bytes,
                 finalEvidenceProof=proof, maxFinalChars=MAX_CHARS, maxFinalBytes=MAX_BYTES)
    return value


def verify_proof(proof, catalog):
    """Compare proof to the trusted reconstructed catalog, not to model fields."""
    from audit_ai.model.semantic_hierarchy import fingerprint
    # Coverage records include exact full redacted text hashes and true bounds.
    if type(proof) is not dict or catalog is None or proof.get("protocol") != PROTOCOL or proof.get("complete") is not True:
        return False
    coverage = []
    for fid, bound in catalog.source_lengths.items():
        rows = [s for s in catalog.segments if s.file_id == fid]
        cursor = 0
        if not rows or len({s.material_category for s in rows}) != 1:
            return False
        for s in rows:
            if s.source_start != cursor or s.source_end - cursor != len(s.exact_text):
                return False
            cursor = s.source_end
        if cursor != bound:
            return False
        coverage.append([fid, rows[0].material_category, bound,
                         hashlib.sha256("".join(s.exact_text for s in rows).encode("utf-8")).hexdigest()])
    ids, files = proof.get("evidenceOrder"), proof.get("fileRegistry")
    if (type(ids) is not list or any(type(e) is not str for e in ids)
            or len(ids) != len(set(ids)) or not set(ids).issubset(catalog.by_id)
            or type(files) is not list or any(type(row) is not list or len(row) != 2 or
                any(type(c) is not str for c in row) for row in files)
            or len(files) != len({tuple(row) for row in files})
            or {tuple(row) for row in files} != {(s.file_id, s.material_category) for s in catalog.segments}):
        return False
    try:
        selected, selections, ranges = _select(proof["selectionInputs"], catalog)
    except (KeyError, TypeError, ValueError, AuditAiError): return False
    if set(ids) != selected or proof.get("selection") != selections or proof.get('visibleRanges') != ranges: return False
    registry=proof.get('evidenceRegistry')
    if (type(registry) is not list or any(type(e)is not str for e in registry)
        or len(registry)!=len(set(registry)) or not set(ids).issubset(registry)
        or not set(registry).issubset(catalog.by_id)):return False
    view = _view(ids, files, catalog, coverage, selections, ranges, registry)
    return (proof.get("viewSha256") == fingerprint(view)
            and proof.get("textEncoding") == TEXT_ENCODING and proof.get("wireViewSha256") == fingerprint(pack_view(view))
            and set(catalog.source_lengths) == {s.file_id for s in catalog.segments}
            and proof.get("sourceCoverage") == coverage and proof.get("fileCount") == len(coverage)
            and proof.get("segmentCount") == len(catalog.segments)
            and proof.get("visibleSegmentCount") == len(ids)
            and proof.get("selectionProtocol") == SELECTION_PROTOCOL
            and proof.get("citableEvidenceIds") == [s.evidence_id for s in catalog.segments if s.citable and s.evidence_id in selected])


def visible_catalog(proof, catalog):
    """Text-validation projection only, NEVER a new frozen source authority.

    A disjoint gap is explicit, so even a substring spanning unseen text cannot
    pass. Public evidence identity/locators continue to use the original catalog.
    Unknown source lengths deliberately cannot authorize global-absence proof.
    """
    from dataclasses import replace
    from audit_ai.model.evidence import EvidenceCatalog
    if not verify_proof(proof, catalog): _fail()
    rows = []
    for eid in proof['evidenceOrder']:
        segment = catalog.by_id[eid]
        text = '\n[未提供原文区间]\n'.join(segment.exact_text[
            start-segment.source_start:end-segment.source_start] for start,end in proof['visibleRanges'][eid])
        rows.append(replace(segment, exact_text=text))
    complete={}
    for fid,length in catalog.source_lengths.items():
        original=[s for s in catalog.segments if s.file_id==fid]
        if all(proof['visibleRanges'].get(s.evidence_id)==[[s.source_start,s.source_end]] for s in original):
            complete[fid]=length
    return EvidenceCatalog(rows,source_lengths=complete)


def fully_visible_evidence_ids(proof,catalog):
    """Only complete original citation ranges can carry inherited prose."""
    if not verify_proof(proof,catalog): _fail()
    return [e for e in proof['evidenceOrder'] if proof['visibleRanges'][e] ==
        [[catalog.by_id[e].source_start,catalog.by_id[e].source_end]]]
