from copy import deepcopy
import pytest
from audit_ai.client_privacy import ClientIdentity
from audit_ai.review_interest import contradictory_actor, project_review_interest


@pytest.mark.parametrize('claim,quote,role,reject', [
    ('乙方放弃救济，导致我方无法通过法定程序追责', '乙方放弃通过诉讼或仲裁救济的权利', 'PARTY_A', True),
    ('乙方承担全部赔偿，导致我方可能承担巨额赔偿债务', '乙方须承担全部赔偿责任，向甲方支付赔偿', 'PARTY_A', True),
    ('甲方不得起诉，导致我方无法起诉', '甲方不得向法院提起诉讼', 'PARTY_B', True),
    ('我方无法诉讼救济', '甲方不得起诉，乙方不得起诉', 'PARTY_A', False),
    ('乙方须承担无上限赔偿，可能影响条款有效性及我方追责', '乙方须承担无上限赔偿', 'PARTY_A', False),
    ('乙方最高赔偿一元，甲方可能无法充分追偿', '乙方最高赔偿一元', 'PARTY_A', False),
    ('付款约定冲突', '验收合格才付款，但又约定验收前付清', 'PARTY_A', False),
    ('我方可能承担巨额赔偿', '乙方须承担赔偿', 'UNMATCHED', False),
    ('甲方可能承担赔偿', '乙方不承担赔偿', 'PARTY_A', False),
])
def test_actor_contradiction_is_narrow_and_preserves_real_risk(claim,quote,role,reject):
    assert contradictory_actor({'explanation':claim},role,[quote]) is reject


@pytest.mark.parametrize('operation,key', [('CONTRACT_CASE_ANALYSIS','findings'),
    ('PROCUREMENT_CASE_REVIEW','findings'),('BUDGET_BATCH_REVIEW','items')])
def test_serious_exception_cannot_bypass_actor_check(operation,key,caplog):
    identity=ClientIdentity(('合成机关',),(('f1','PARTY_A'),))
    quote='乙方须承担无上限赔偿，并放弃诉讼救济。'
    bad={'title':'风险','explanation':'我方可能承担巨额赔偿债务', 'citations':[{'document':'f1','text':quote}]}
    good={**bad,'explanation':'无上限赔偿条款的可执行性需要关注，影响甲方实际追责。'}
    payload={key:[bad,good],'facts':[{'value':'120000元'}],'clauses':[{'summary':quote}]}
    before=deepcopy(payload)
    result=project_review_interest(payload,identity,{},operation,'synthetic')
    assert result[key]==[good]
    assert payload==before and result['facts']==payload['facts'] and result['clauses']==payload['clauses']
    assert 'CITED_ACTOR_CONTRADICTION' in caplog.text
    assert quote not in caplog.text and '巨额赔偿' not in caplog.text
