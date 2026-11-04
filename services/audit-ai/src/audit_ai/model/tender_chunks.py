"""F10: complete JSON envelopes, original evidence and bounded local context."""
from dataclasses import dataclass, replace
import json
import re
from audit_ai.errors import AuditAiError
from audit_ai.model.material_chunks import SourcePiece, split_piece


@dataclass(frozen=True)
class TenderUnit:
    piece: SourcePiece
    page: int | None = None
    confidence: float | None = None
    citable: bool = True
    comparison: bool = False


def split_units(units):
    parts=split_piece(units[0].piece)
    return [[replace(units[0],piece=p)] for p in parts] if parts else None


def render_tender(units, rules, context_chars):
    segments=[];supplementary=[]
    for unit in units:
        view=unit.piece.view(context_chars)
        if unit.citable:
            view['sourceLocator']=view.pop('locator')
            view.update(pageNumber=unit.page,confidence=unit.confidence)
            segments.append(view)
        else:
            # Preserve fullText content absent from segments without inventing
            # a citable sourceLocator for it.
            view.pop('locator');supplementary.append(view)
    local={s['sourceLocator']:s.get('contextBefore','')+s['text']+s.get('contextAfter','') for s in segments}
    scoped=[r.model_dump(mode='json',by_alias=True) for r in rules
            if r.source_locator in local and r.matched_text in local[r.source_locator]]
    payload={'segments':segments,'supplementaryText':supplementary,'ruleFindings':scoped}
    if units and all(unit.comparison for unit in units):
        payload['reviewFocus']='CROSS_SOURCE_QUANTITATIVE_COMPARISON'
    return json.dumps(payload,ensure_ascii=False,separators=(',',':'))


def prepare_tender(request, settings, text_parts):
    if len({s.source_locator for s in request.segments})!=len(request.segments):
        raise AuditAiError(422,'MODEL_INVALID_RESULT','duplicate tender source locator')
    units=[]
    sources=[TenderUnit(SourcePiece(s.source_locator,s.text),s.page_number,s.confidence) for s in request.segments]
    normalize=lambda s:re.sub(r'\s+','',s)
    if normalize(request.full_text)!=normalize(''.join(s.text for s in request.segments)):
        sources.append(TenderUnit(SourcePiece('',request.full_text),citable=False))
    for unit in sources:
        start=0
        for part in text_parts(unit.piece.source,limit=min(1800,settings.material_chunk_chars//2)):
            units.append(replace(unit,piece=SourcePiece(unit.piece.locator,unit.piece.source,start,start+len(part))))
            start+=len(part)
    queue=list(units);fitted=[]
    def fits(chunk):
        source=render_tender(chunk,request.rule_findings,settings.material_context_chars)
        return len(source)<=min(settings.model_chunk_chars,settings.material_chunk_chars) and len(source.encode())<=settings.material_chunk_bytes
    while queue:
        unit=queue.pop(0)
        if fits([unit]):fitted.append(unit);continue
        parts=split_units([unit])
        if not parts:raise AuditAiError(413,'MODEL_INPUT_TOO_LARGE','tender unit metadata exceeds limit')
        queue[:0]=[part[0] for part in parts]
    chunks=[];current=[]
    for unit in fitted:
        if current and (len(current)>=6 or not fits([*current,unit])
                        or any(u.piece.locator==unit.piece.locator for u in current)):
            chunks.append(current);current=[]
        current.append(unit)
    if current:chunks.append(current)
    if len(chunks)>settings.tender_max_chunks:
        raise AuditAiError(413,'MODEL_INPUT_TOO_LARGE','tender document exceeds chunk budget')
    return chunks


def crosscheck_tender(request, settings):
    """Compare distant quantitative clauses using original citable spans.

    No generated summary is treated as evidence. Exhausting this explicit
    comparison budget is an incomplete review, never a clean bill of health.
    """
    topics={
        'delivery':r'工期|交付|交货|履约期限|服务期限',
        'payment':r'付款|支付|预付|尾款|结算',
        'warranty':r'质保|保修',
        'bond':r'保证金|担保金额',
        'validity':r'投标有效期',
    }
    groups={name:[] for name in topics};seen={name:set() for name in topics}
    for segment in request.segments:
        for match in re.finditer(r'[^。；;\n]+[。；;\n]?',segment.text):
            clause=match.group()
            if not re.search(r'[0-9０-９壹贰叁肆伍陆柒捌玖一二三四五六七八九十]',clause):continue
            for name,pattern in topics.items():
                if not re.search(pattern,clause):continue
                normalized=re.sub(r'\s+','',clause)
                if normalized in seen[name]:continue
                seen[name].add(normalized)
                if len(clause)>600:
                    raise AuditAiError(413,'MODEL_CROSSCHECK_LIMIT','quantitative clause cannot fit crosscheck budget')
                groups[name].append(TenderUnit(SourcePiece(segment.source_locator,segment.text,match.start(),match.end()),segment.page_number,segment.confidence))
    pairs=[];identities=set()
    for group in groups.values():
        for i,first in enumerate(group):
            for second in group[i+1:]:
                # The local pass already reads adjacent clauses in one source.
                if first.piece.locator==second.piece.locator and abs(first.piece.start-second.piece.start)<500:continue
                key=((first.piece.locator,first.piece.start),(second.piece.locator,second.piece.start))
                if key in identities:continue
                identities.add(key);pairs.append([replace(first,comparison=True),replace(second,comparison=True)])
                if len(pairs)>64:
                    raise AuditAiError(413,'MODEL_CROSSCHECK_LIMIT','too many quantitative clause comparisons')
                rendered=render_tender(pairs[-1],[],settings.material_context_chars)
                if len(rendered)>min(settings.model_chunk_chars,settings.material_chunk_chars) or len(rendered.encode())>settings.material_chunk_bytes:
                    raise AuditAiError(413,'MODEL_CROSSCHECK_LIMIT','quantitative comparison exceeds chunk bounds')
    return pairs
