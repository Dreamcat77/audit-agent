"""Finite, source-visible scope refinement; not general natural entailment.

The original unknown scope is retained. Every supported positive target still
runs through the ordinary type, evidence, safety and critical-fact validators.
"""
import hashlib
import re
from copy import deepcopy

PROTOCOL='contract-scope-refinement-v1'


def termination_actor_pattern(actor):
    # Finite procedural alternative, not an arbitrary gap between actor and
    # action: no different party, negation or unrelated act may be skipped.
    return actor+r'(?:有权|可以|可|应当|应)?(?:(?:解除|终止)|(?:按约定程序)?发出(?:解除|终止)通知)'


def validate(original,row,finals,catalog,allowed,scope_hashes,proof,index):
    from audit_ai.model.semantic_quality import QualityFailure
    from audit_ai.model.semantic_final_evidence import verify_proof,visible_catalog
    from audit_ai.model.evidence import normalize_visible_text
    def reject():raise QualityFailure('SCOPE_REFINEMENT_UNPROVEN','resolutions',index)
    scope=original.get('obligationScope',{});witness=original.get('workScopeWitness',{})
    refs=set(original['comparisonEvidenceIds']);basis=set(row.basis_evidence_ids)
    if (original.get('claimKind')!='LOCAL_NOT_SEEN' or original['attention']!='INSUFFICIENT'
        or original['problemEvidenceIds'] or original.get('unavailableMaterialCategory') is not None
        or witness.get('protocol')!='contract-work-scope-witness-v1'
        or witness.get('type')!='SCOPE_WITNESS_NOT_FINAL_EVIDENCE'
        or witness.get('hintIndex')!=row.observation[1] or witness.get('scopeHash') not in scope_hashes
        or not refs or set(witness.get('witnessEvidenceIds',()))!=refs
        or not verify_proof(proof,catalog) or not basis or len(basis)!=len(row.basis_evidence_ids)
        or not basis.issubset(allowed) or not refs.issubset(catalog.by_id)
        or not row.final_hint_indexes or len(set(row.final_hint_indexes))!=len(row.final_hint_indexes)
        or any(type(i)is not int or not 0<=i<len(finals) for i in row.final_hint_indexes)):reject()
    visible=visible_catalog(proof,catalog)
    if (not basis.issubset(visible.by_id) or any(not visible.by_id[e].citable for e in basis)
        or {catalog.by_id[e].file_id for e in refs}!={visible.by_id[e].file_id for e in basis}):reject()
    targets=[finals[i] for i in row.final_hint_indexes]
    if any(t.get('claimKind')!='CLAUSE_PRESENT' or t['attention']!='INFO' or t['problemEvidenceIds']
        or t['category']!=original['category'] for t in targets):reject()
    target_ids={e for t in targets for e in t['comparisonEvidenceIds']}
    if (not basis.issubset(target_ids) or not target_ids.issubset(allowed)
        or not target_ids.issubset(visible.by_id)
        or {visible.by_id[e].file_id for e in target_ids}!={catalog.by_id[e].file_id for e in refs}):reject()
    if any((scope.get('responsibleParty')!='UNSPECIFIED' and t['obligationScope']['responsibleParty']!=scope.get('responsibleParty'))
        or (scope.get('trigger') is not None and t['obligationScope']['trigger']!=scope.get('trigger')) for t in targets):reject()
    texts=[visible.by_id[e].exact_text for e in sorted(basis)]
    joined='\n'.join(texts)
    old_text=original['title']+'。'+original['description']
    facets=[]; atoms=[]
    def locate(facet,pattern):
        found=[]
        for eid in sorted(basis):
            segment=catalog.by_id[eid]
            for start,end in proof['visibleRanges'][eid]:
                text=segment.exact_text[start-segment.source_start:end-segment.source_start]
                for match in re.finditer(pattern,text):
                    found.append({'facet':facet,'evidenceId':eid,'sourceStart':start+match.start(),
                        'sourceEnd':start+match.end(),'textSha256':hashlib.sha256(match.group().encode()).hexdigest()})
        return found
    topic=scope.get('topic')
    if topic=='PROJECT_IDENTITY' and original['category']=='PROJECT_IDENTITY':
        if any(t['obligationScope']!={'topic':'PROJECT_IDENTITY','responsibleParty':'BOTH','trigger':None}
               for t in targets):reject()
        patterns={
            'PROJECT_NAME':r'项目名称[^\S\r\n]*[:：\t][^\S\r\n]*[^\s:：\t\r\n][^\n]*',
            'PROJECT_NUMBER':r'项目(?:编号|号)[^\S\r\n]*[:：\t][^\S\r\n]*[A-Za-z0-9][A-Za-z0-9_-]+',
            'PARTY_A_NAME':r'甲方(?:[（(][^\n）)]{1,20}[）)])?[^\S\r\n]*[:：\t][^\S\r\n]*[^\s:：\t\r\n][^\n]*',
            'PARTY_B_NAME':r'乙方(?:[（(][^\n）)]{1,20}[）)])?[^\S\r\n]*[:：\t][^\S\r\n]*[^\s:：\t\r\n][^\n]*'}
        required={'PROJECT_NAME','PROJECT_NUMBER'}
        if re.search('甲乙|双方|主体|法定名称|完整.{0,4}名称',old_text):required|={'PARTY_A_NAME','PARTY_B_NAME'}
        for name in sorted(required):
            matches=list(re.finditer(patterns[name],joined))
            if not matches:reject()
            values=[re.split(r'[:：\t]',m.group(),maxsplit=1)[-1].strip() for m in matches]
            if (any(re.fullmatch(r'(?:未填写|待填写|待确认|空白|N/?A|[-—_/]+)(?:[（(].*)?',v,re.I) for v in values)
                or any(re.match(r'(?:项目名称|项目编号|甲方|乙方)[^\S\r\n]*[:：]',v) for v in values)):reject()
            evidence=locate(name,patterns[name])
            if not evidence:reject()
            atoms.extend(evidence)
            facets.append(name)
        # Static identifiers do not acquire an invented obligation trigger.
    elif topic in {'DELAY_LIABILITY','TERMINATION'}:
        if original['category'] not in {'BREACH_LIABILITY','TERM_TERMINATION'}:reject()
        if topic=='DELAY_LIABILITY':
            if not re.search('交付|交货',old_text):reject()
            trigger=r'(?:逾期|延期|延迟|迟延).{0,16}(?:交付|交货)|(?:交付|交货).{0,16}(?:逾期|延期|延迟|迟延)|未按.{0,20}(?:时间|期限|约定).{0,20}(?:交付|交货)|未.{0,8}(?:按期|按时|如期).{0,8}(?:交付|交货)'
            if not any(re.search(trigger,line) and re.search('违约|赔偿|责任|违约金',line) for text in texts for line in text.splitlines()):reject()
            facets=['DELIVERY_DELAY_LIABILITY']
            atoms.extend(locate('DELIVERY_DELAY_LIABILITY',trigger))
            # A scoped label must not erase another explicit facet in the
            # immutable observation. Do not use delay evidence alone to close
            # a mixed delay/functional-defect question.
            if re.search(r'功能缺陷|质量缺陷|缺陷|不合格',old_text):
                target_text='\n'.join(t['title']+'。'+t['description'] for t in targets)
                if not re.search(r'功能缺陷|质量缺陷|缺陷|不合格',target_text):reject()
                party=scope.get('responsibleParty')
                if party not in {'PARTY_A','PARTY_B'}:reject()
                actor={'PARTY_A':'甲方','PARTY_B':'乙方'}[party]
                middle=r'(?:(?!甲方|乙方)[^。；\n]){0,40}'
                defect=r'(?:功能缺陷|质量缺陷|缺陷|不合格)'
                action=r'(?:修复|整改|承担|负责|赔偿)'
                pattern=r'(?:'+actor+middle+defect+middle+action+'|'+defect+middle+actor+middle+action+')'
                matching=[part for text in texts for part in re.split(r'[。；\n]',text) if re.search(pattern,part)]
                if not matching or all(re.search(actor+middle+r'(?:不承担|无需承担|不负责|不应承担)',part) for part in matching):reject()
                facets.append('DEFECT_LIABILITY_CLAUSE')
                atoms.extend(locate('DEFECT_LIABILITY_CLAUSE',pattern))
        else:
            required=[]
            if re.search('解除|终止',old_text):required.append(('TERMINATION',r'解除|终止'))
            if re.search('生效|效力',old_text):required.append(('EFFECTIVENESS',r'生效'))
            if not required:reject()
            for name,pattern in required:
                if not re.search(pattern,joined):reject()
                facets.append(name)
                atoms.extend(locate(name,pattern))
            if not set(facets).issubset({t['obligationScope']['topic'] for t in targets}):reject()
        for target in targets:
            current=target['obligationScope'];party=current['responsibleParty'];value=current['trigger']
            expected_topics={'DELAY_LIABILITY'} if topic=='DELAY_LIABILITY' else {'TERMINATION','EFFECTIVENESS'}
            if current['topic'] not in expected_topics or party not in {'PARTY_A','PARTY_B','BOTH'}:reject()
            if scope.get('responsibleParty')!='UNSPECIFIED' and party!=scope.get('responsibleParty'):reject()
            if scope.get('trigger') is not None and value!=scope['trigger']:reject()
            if not isinstance(value,str) or not normalize_visible_text(value):reject()
            local=[visible.by_id[e].exact_text for e in target['comparisonEvidenceIds'] if e in basis]
            party_re={'PARTY_A':r'甲方','PARTY_B':r'乙方','BOTH':r'双方|甲乙|甲方.*乙方'}[party]
            if not any(re.search(party_re,text,re.S) for text in local):reject()
            if topic=='DELAY_LIABILITY':
                actor={'PARTY_A':'甲方','PARTY_B':'乙方','BOTH':'(?:双方|甲乙双方)'}[party]
                middle=r'(?:(?!甲方|乙方)[^。；\n]){0,80}'
                if not any(re.search(actor+middle+r'(?:'+trigger+r')'+middle+r'(?:违约|责任|赔偿)',text) for text in local):reject()
            elif current['topic']=='TERMINATION':
                actor={'PARTY_A':'甲方','PARTY_B':'乙方','BOTH':'(?:双方|甲乙双方)'}[party]
                clauses=[part for text in local for part in re.split(r'[。；\n]',text)]
                if not any(normalize_visible_text(value) in normalize_visible_text(part)
                    and re.search(termination_actor_pattern(actor),part)
                    and re.search(r'时|若|如|因|发生|情形|条件|在.{0,20}下|(?:(?:累计|连续).{0,12}(?:延误|延期)|(?:延误|延期).{0,12}累计).{0,30}(?:通知|催告)(?:后)?[，,\s]*仍',part) for part in clauses):reject()
            # The sole finite paraphrase mapping is delivery-delay; otherwise
            # an originally unknown trigger needs an exact visible source span.
            paraphrase=(topic=='DELAY_LIABILITY' and value in {'交付延期','延期交付','逾期交付'})
            if not paraphrase and not any(normalize_visible_text(value) in normalize_visible_text(t) for t in local):reject()
    else:reject()  # No qualification, subcontracting or generic OTHER shortcut.
    return {'protocol':PROTOCOL,'observation':list(row.observation),'originalScope':deepcopy(scope),
        'targetScopes':[deepcopy(t['obligationScope']) for t in targets], 'facets':facets,
        'fieldEvidenceAtoms':atoms,
        'basisVisibleRanges':{e:deepcopy(proof['visibleRanges'][e]) for e in sorted(basis)},
        'basisTextSha256':{e:hashlib.sha256(visible.by_id[e].exact_text.encode()).hexdigest() for e in sorted(basis)},
        'originalWorkScopeHash':witness['scopeHash'],'automaticUnknownClosure':False}
