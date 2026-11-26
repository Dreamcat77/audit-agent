"""Client-centred review policy; factual extraction and evidence are never rewritten."""
from __future__ import annotations

import json
import re
from audit_ai.logging import log_event
import logging

STANCE_POLICY = (
    '审查立场必须贯穿预算、采购、合同及汇总：维护已配置权益保护方的合法权益，以其资金安全、'
    '履约效果、质量验收、追责救济、保密及持续使用为重点。受保护实体及其已确认角色才是我方，'
    '甲方和乙方不固定代表我方，不能按称谓或付款惯例替换实际身份。'
    '不要仅因正常条款对非立场方不利、其义务较多或商业便利较少就提出问题；'
    '不得为照顾非立场方而建议削弱我方已有的验收后付款、质量保证、保密、解约、赔偿或追责权利，'
    '不能把这类建议改成INFO、观察、可选完善后继续输出。'
    '例如我方为采购人时，验收合格后支付、合理质保和有明确上限的违约责任不因供应商资金压力而成为问题。'
    '例外是原文支持的明显违法或强制性规定冲突、严重失衡、无限或显著不成比例责任，'
    '以及会损害我方合同效力、有效竞争、供应履约、审计问责或合法救济的实质风险；'
    '这些仍应报告，说明具体条款、业务影响及证据，不凭常识编造法规或笼统降低对方责任。'
    '严重例外也必须核对责任和权利归属：对方承担的违约金、赔偿或债务不能写成我方承担；'
    '仅限制对方起诉或仲裁的条款，不能据此说我方不能起诉或维权。'
    '仅对方放弃救济，不证明我方起诉、仲裁或追责受到影响，也不能把条款可能无效推演为我方诉权受损；'
    '如原文缺乏我方权利受限依据，只客观说明对方救济被排除及条款效力需要核查，不猜测裁判结论。'
    '例如我方为甲方、乙方承担无上限赔偿时，可核查过度责任对条款有效性、可执行性及我方追责效果的影响，'
    '不得说我方因此承担乙方的赔偿债务，也不能只以对方负担较重为由要求减轻。'
    '立场限制不改变事实提取、金额精确核算、跨文件真实差异及原文证据。'
    '身份未明确时只作客观核查，不猜测我方、不替任何一方争取商业利益，也不要求用户确认立场。'
    '输出前逐项自检：指出的是我方实质风险、客观问题或上述严重例外吗？'
    '每个我方受损结论都要与原文对应，核对谁付款、谁赔偿、谁被限制权利，不可把对方处境换成我方。'
    '如果只是替非立场方减负、争取提前付款或批评对其不利且没有这些风险，就删除该意见；'
    '不能为了凑问题或填满分类而保留。中性条款摘要保留原约定，不添加反向建议。'
)

REVIEW_OPERATIONS = frozenset({
    'CONTRACT_CASE_ANALYSIS', 'PROCUREMENT_CASE_REVIEW', 'BUDGET_BATCH_REVIEW',
    'CONTRACT_TEXT_ANALYSIS',
})
_COUNTER = {
    'PARTY_A': ('乙方',), 'PARTY_B': ('甲方',),
    'PURCHASER': ('供应商', '投标人', '承包人', '承包方'),
    'PRINCIPAL': ('受托方', '受托人', '服务方'),
    'BUYER': ('卖方', '供方'), 'SELLER': ('买方', '需方'),
    'SUPPLIER': ('采购人', '采购方'), 'SERVICE_PROVIDER': ('委托方', '委托人'),
    'EMPLOYER': ('承包人', '承包方'), 'CONTRACTOR': ('发包人', '发包方'),
    'LESSOR': ('承租方', '承租人'), 'LESSEE': ('出租方', '出租人'),
}
_OWN = {key: tuple(_COUNTER[opposite]) for key, opposite in (
    ('PARTY_A', 'PARTY_B'), ('PARTY_B', 'PARTY_A'), ('PURCHASER', 'SUPPLIER'),
    ('PRINCIPAL', 'SERVICE_PROVIDER'), ('BUYER', 'SELLER'), ('SELLER', 'BUYER'),
    ('SUPPLIER', 'PURCHASER'), ('SERVICE_PROVIDER', 'PRINCIPAL'),
    ('EMPLOYER', 'CONTRACTOR'), ('CONTRACTOR', 'EMPLOYER'),
    ('LESSOR', 'LESSEE'), ('LESSEE', 'LESSOR'),
)}
_SERIOUS = re.compile(
    r'违法|违反.{0,12}(?:法律|强制|法定)|无效|不可执行|无法履行|履约中断|供应中断|'
    r'合同效力|审计问责|限制竞争|排他|歧视|人身伤害|故意|重大过失|无限责任|'
    r'无上限|全部损失|严重失衡|明显失衡|显失公平|放弃全部|剥夺.{0,8}(?:救济|诉权)|'
    r'格式条款.{0,8}(?:无效|提示说明)|强制性规定|百分之百|100\s*%'
)
_PROSE = ('title', 'explanation', 'suggestion', 'rationale', 'remediationSuggestion',
          'reason', 'recommendation', 'riskDescription')


def _context(source):
    try:
        value = json.loads(source) if isinstance(source, str) else source
        return value if isinstance(value, dict) else {}
    except (ValueError, TypeError):
        return {}


def _role_for(item, roles, references):
    selected = []
    for citation in item.get('citations', []) if isinstance(item.get('citations'), list) else []:
        if isinstance(citation, dict):
            selected.append(roles.get(citation.get('document'), 'UNMATCHED'))
    for key in ('evidenceIds', 'problemEvidenceIds', 'comparisonEvidenceIds'):
        if isinstance(item.get(key), list):
            selected.extend(roles.get(references.get(ref, ''), 'UNMATCHED') for ref in item[key] if isinstance(ref, str))
    if not selected:
        selected = list(roles.values())
    distinct = set(selected)
    return next(iter(distinct)) if len(distinct) == 1 else None


def counterparty_only(item, role):
    """Reject only explicit ordinary commercial advocacy, never general risk words."""
    if role not in _COUNTER:
        return False
    # Quotes, facts and clause summaries cannot trigger this decision.
    prose = ''.join(item.get(key, '') for key in _PROSE if isinstance(item.get(key), str))
    if _SERIOUS.search(prose):
        return False
    own = '(?:我方|立场方|权益保护方|' + '|'.join(_OWN[role]) + ')'
    if re.search(r'对' + own + r'.{0,16}(?:不利|风险|损失|损害|无法|不确定)', prose):
        return False
    if re.search(own + r'.{0,18}(?:无法|不能|难以|不足以|受损|受限|面临|缺乏|丧失|无权)', prose):
        return False
    if re.search(own + r'.{0,22}(?:风险|损失|损害|履约中断|追责障碍)', prose):
        return False
    counter = '(?:' + '|'.join(_COUNTER[role]) + ')'
    return bool(re.search(
        r'(?:对' + counter + r'.{0,16}(?:不利|不公平)|'
        + counter + r'.{0,18}(?:负担过重|责任过重|义务过重|资金压力|权益受损|缺乏保护|利益受损)|'
        r'(?:减轻|降低|保障|维护|保护)' + counter + r'.{0,14}(?:负担|责任|义务|权益|利益)|'
        r'为' + counter + r'.{0,12}(?:提前付款|提前支付|预付款|资金周转))', prose))


def contradictory_actor(item, role, evidence):
    """Only explicit opposite actors contradict; unknown roles stay unknown.

    This is a narrow evidence check, not a general legal entailment classifier.
    Serious exceptions still need truthful attribution of rights and debts.
    """
    if role not in {'PARTY_A', 'PARTY_B'}:
        return False
    own = '甲方' if role == 'PARTY_A' else '乙方'
    prose = ''.join(item.get(key, '') for key in ('title', 'explanation', 'rationale', 'riskDescription')
                    if isinstance(item.get(key), str))
    prose = re.sub(r'我方|立场方|权益保护方', own, prose)
    quotes = [q for q in evidence if isinstance(q, str)]
    patterns = (
        r'[^甲乙。；;\n]{0,12}(?:不得|不能|无法|无权|放弃|丧失|失去)[^甲乙。；;\n]{0,22}(?:起诉|诉讼|仲裁|救济|维权|追责)',
        r'[^甲乙不未无。；;\n]{0,8}(?:须|应|必须|将|需|可能)?承担[^甲乙。；;\n]{0,16}(?:赔偿|债务|违约金)',
    )
    for actor, other in (('甲方', '乙方'), ('乙方', '甲方')):
        for predicate in patterns:
            if (re.search(actor + predicate, prose)
                    and any(re.search(other + predicate, q) for q in quotes)
                    and not any(re.search(actor + predicate, q) for q in quotes)):
                return True
    return False


def unsupported_own_remedy_inference(item, role, evidence):
    """Remove a specific unsupported causal tail, keeping the serious issue."""
    if role not in {'PARTY_A', 'PARTY_B'}:
        return item
    own, other = ('甲方', '乙方') if role == 'PARTY_A' else ('乙方', '甲方')
    restriction = r'[^甲乙。；;\n]{0,12}(?:不得|不能|无法|无权|放弃|丧失|失去)[^甲乙。；;\n]{0,22}(?:起诉|诉讼|仲裁|救济|维权|追责)'
    quotes = [q for q in evidence if isinstance(q, str)]
    if not any(re.search(other + restriction, q) for q in quotes) or any(re.search(own + restriction, q) for q in quotes):
        return item
    result = dict(item)
    # This addresses the observed unsupported conclusion, not every possible
    # legal inference. If other evidence directly restricts our rights, keep it.
    tail = re.compile(r'，(?:可能|进而|从而)?影响(?:我方|立场方|权益保护方|'+own+r')[^。；;\n]{0,35}(?:诉讼|仲裁|诉权|维权|追责)[^。；;\n]{0,12}(?:效力|权利|权|能力|途径|程序)[^。；;\n]*')
    for key in ('explanation', 'rationale', 'riskDescription'):
        if isinstance(result.get(key), str):
            result[key] = tail.sub('', result[key])
    return result


def project_review_interest(payload, identity, source, operation, request_id):
    if identity is None or operation not in REVIEW_OPERATIONS or not isinstance(payload, dict):
        return payload
    context = _context(source)
    roles = dict(identity.parties)
    # This context is server-built input, never a role supplied by the model.
    explicit = context.get('reviewParties', {})
    if isinstance(explicit, dict):
        roles.update({k: v for k, v in explicit.items() if k in roles or k.startswith('D')})
    references = {}
    excerpts = {}
    for entry in context.get('evidence', []) if isinstance(context.get('evidence'), list) else []:
        if isinstance(entry, dict):
            references[entry.get('id')] = entry.get('document', entry.get('documentId', entry.get('taskId')))
            excerpts[entry.get('id')] = entry.get('text', entry.get('excerpt', ''))
    removed = 0
    contradicted = 0
    def project(value):
        nonlocal removed, contradicted
        if not isinstance(value, dict):
            return value
        out = dict(value)
        for key in ('findings', 'items', 'observations', 'hints'):
            if not isinstance(value.get(key), list):
                continue
            kept = []
            for item in value[key]:
                # A terse explanation cannot erase a serious exception visible
                # in the cited source. Inspect only cited material, not the batch.
                evidence = []
                if isinstance(item, dict):
                    for field in ('evidenceIds', 'problemEvidenceIds', 'comparisonEvidenceIds'):
                        if isinstance(item.get(field), list):
                            evidence.extend(excerpts.get(ref, '') for ref in item[field] if isinstance(ref, str))
                    if isinstance(item.get('citations'), list):
                        evidence.extend(c.get('text', '') for c in item['citations'] if isinstance(c, dict))
                serious = any(isinstance(text, str) and _SERIOUS.search(text) for text in evidence)
                role = _role_for(item, roles, references) if isinstance(item, dict) else None
                if isinstance(item, dict) and contradictory_actor(item, role, evidence):
                    contradicted += 1
                elif isinstance(item, dict) and not serious and counterparty_only(item, role):
                    removed += 1
                else:
                    kept.append(unsupported_own_remedy_inference(item, role, evidence) if isinstance(item, dict) else item)
            out[key] = kept
        return out
    result = project(payload)
    if removed:
        log_event(logging.getLogger('audit_ai.review_interest'), 'CLIENT_INTEREST_PROJECTION',
                  request_id=request_id, operation=operation, stage='RESULT_VALIDATION',
                  outcome='COUNTERPARTY_ADVICE_REMOVED', filtered_hint_count=removed)
    if contradicted:
        log_event(logging.getLogger('audit_ai.review_interest'), 'CLIENT_INTEREST_PROJECTION',
                  request_id=request_id, operation=operation, stage='RESULT_VALIDATION',
                  outcome='CITED_ACTOR_CONTRADICTION', filtered_hint_count=contradicted)
    return result
