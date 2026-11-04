import json
import pytest
from fastapi.testclient import TestClient
from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import ModelCompletion


def body(qualification='台式计算机允许同等性能品牌参与。'):
    segments=[{'sourceLocator':'p1','pageNumber':1,'text':'台式计算机必须使用星桥品牌。'},
              {'sourceLocator':'p2','pageNumber':2,'text':'一般说明。'*400},
              {'sourceLocator':'p3','pageNumber':3,'text':qualification}]
    return dict(projectId='synthetic',reviewId='synthetic',segments=segments,
                fullText='\n'.join(s['text'] for s in segments),ruleFindings=[])


class Gateway:
    def __init__(self,decision='CONTEXT_DEPENDENT',invalid=None):self.sources=[];self.decision=decision;self.invalid=invalid
    def analyze_tender_compliance(self,op):
        source=json.loads(op.source_text);self.sources.append(source)
        if source.get('reviewFocus')=='COUNTEREVIDENCE_REVIEW':
            context=source['qualifyingSources'][0]
            evidence=dict(sourceLocator=context['sourceLocator'],excerpt=context['text'])
            decision=dict(candidateId=source['candidateId'],decision=self.decision,
                reason='需核对同等产品说明是否适用于该台式计算机。',evidence=[evidence])
            if self.invalid=='foreign':evidence['sourceLocator']='p2'
            if self.invalid=='invented':evidence['excerpt']='不存在的免责条款'
            if self.invalid=='identity':decision['candidateId']='other-candidate'
            if self.invalid=='empty':decision['evidence']=[]
            if self.invalid=='partial':return ModelCompletion(payload={'decisions':[]},model='test',duration_ms=1)
            if self.invalid=='truncated':return ModelCompletion(payload={'decisions':[decision]},model='test',duration_ms=1,finish_reason='LENGTH')
            if self.invalid=='model':return ModelCompletion(payload={'decisions':[decision]},model='changed-model',duration_ms=1)
            return ModelCompletion(payload={'decisions':[decision]},model='test',duration_ms=1)
        findings=[]
        if any('必须使用星桥品牌' in s['text'] for s in source['segments']):
            findings=[dict(matchedText='台式计算机必须使用星桥品牌。',sourceLocator='p1',pageNumber=1,
                riskCategory='指定品牌',riskLevel='WARNING',rationale='存在品牌限制。',
                remediationSuggestion='核对是否允许同等产品。',confidence=.9)]
        return ModelCompletion(payload={'findings':findings},model='test',duration_ms=1)


@pytest.mark.parametrize('decision',['RETAIN','CONTEXT_DEPENDENT','REBUTTED'])
def test_distant_qualification_is_reviewed_against_same_candidate_and_preserves_both_originals(decision):
    gateway=Gateway(decision)
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        response=client.post('/ai/v1/agent/procurement-tender-compliance',json=body())
    assert response.status_code==200,response.text
    finding=response.json()['data']['findings'][0]
    assert finding['matchedText']=='台式计算机必须使用星桥品牌。'
    assert '台式计算机允许同等性能品牌参与' in finding['rationale']
    assert finding['riskLevel']==('INFO' if decision=='REBUTTED' else 'WARNING')
    plans=[s for s in gateway.sources if s.get('reviewFocus')=='COUNTEREVIDENCE_REVIEW']
    assert len(plans)==1 and plans[0]['qualifyingSources'][0]['sourceLocator']=='p3'


@pytest.mark.parametrize('invalid',['foreign','invented','identity','empty','partial','truncated','model'])
def test_incomplete_or_ungrounded_reconciliation_never_publishes_partial_clean_result(invalid):
    with TestClient(create_app(Settings(),model_gateway=Gateway(invalid=invalid))) as client:
        response=client.post('/ai/v1/agent/procurement-tender-compliance',json=body())
    assert response.status_code==502,response.text


def test_rule_only_candidate_also_receives_context_but_original_rule_is_not_deleted():
    data=body();data['ruleFindings']=[dict(ruleCode='synthetic-rule',riskCategory='指定品牌',riskLevel='CRITICAL',
        matchedText='台式计算机必须使用星桥品牌。',sourceLocator='p1',remediationSuggestion='请核对原文。')]
    class RuleOnly(Gateway):
        def analyze_tender_compliance(self,op):
            if json.loads(op.source_text).get('reviewFocus')!='COUNTEREVIDENCE_REVIEW':
                return ModelCompletion(payload={'findings':[]},model='test',duration_ms=1)
            return super().analyze_tender_compliance(op)
    with TestClient(create_app(Settings(),model_gateway=RuleOnly(decision='REBUTTED'))) as client:
        response=client.post('/ai/v1/agent/procurement-tender-compliance',json=data)
    assert response.status_code==200,response.text
    assert len(response.json()['data']['findings'])==1
    assert response.json()['data']['findings'][0]['sourceLocator']=='p1'


def test_multiple_candidates_use_valid_bounds_and_keep_separate_evidence_decisions():
    data=body()
    data['ruleFindings']=[dict(ruleCode='synthetic-rule',riskCategory='品牌限制',riskLevel='CRITICAL',
        matchedText='台式计算机必须使用星桥品牌。',sourceLocator='p1',remediationSuggestion='请核对原文。')]
    class Bounded(Gateway):
        def __init__(self):super().__init__();self.bounds=[]
        def analyze_tender_compliance(self,op):
            if json.loads(op.source_text).get('reviewFocus')=='COUNTEREVIDENCE_REVIEW':
                self.bounds.append((op.chunk_index,op.chunk_count))
            return super().analyze_tender_compliance(op)
    gateway=Bounded()
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        response=client.post('/ai/v1/agent/procurement-tender-compliance',json=data)
    assert response.status_code==200,response.text
    assert gateway.bounds==[(1,2),(2,2)]
    assert {f['riskCategory'] for f in response.json()['data']['findings']}=={'指定品牌','品牌限制'}


def test_invalid_later_candidate_cannot_publish_earlier_reconciled_partial_result():
    data=body()
    data['ruleFindings']=[dict(ruleCode='synthetic-rule',riskCategory='品牌限制',riskLevel='CRITICAL',
        matchedText='台式计算机必须使用星桥品牌。',sourceLocator='p1',remediationSuggestion='请核对原文。')]
    class InvalidSecond(Gateway):
        def analyze_tender_compliance(self,op):
            if (json.loads(op.source_text).get('reviewFocus')=='COUNTEREVIDENCE_REVIEW'
                    and op.chunk_index==2):self.invalid='identity'
            return super().analyze_tender_compliance(op)
    with TestClient(create_app(Settings(),model_gateway=InvalidSecond())) as client:
        response=client.post('/ai/v1/agent/procurement-tender-compliance',json=data)
    assert response.status_code==502,response.text
    assert response.json().get('data') is None


def test_excess_qualification_context_stops_instead_of_silently_truncating_or_choosing_one():
    data=body(''.join(f'允许同等品牌参与，但需核对设备编号{i}的适用对象。' for i in range(160)))
    with TestClient(create_app(Settings(),model_gateway=Gateway())) as client:
        response=client.post('/ai/v1/agent/procurement-tender-compliance',json=data)
    assert response.status_code==413,response.text
    assert response.json()['code']=='MODEL_RECONCILIATION_LIMIT'


def test_local_evidence_cannot_authorize_a_document_wide_absence_claim():
    class Missing(Gateway):
        def analyze_tender_compliance(self,op):
            result=super().analyze_tender_compliance(op)
            for finding in result.payload.get('findings',[]):finding['rationale']='整份材料未约定付款条款。'
            return result
    with TestClient(create_app(Settings(),model_gateway=Missing())) as client:
        response=client.post('/ai/v1/agent/procurement-tender-compliance',json=body())
    assert response.status_code==502,response.text


def test_unrelated_brand_exception_is_not_used_to_review_a_text_quality_finding():
    class TextQuality(Gateway):
        def analyze_tender_compliance(self,op):
            result=super().analyze_tender_compliance(op)
            for finding in result.payload.get('findings',[]):
                finding.update(matchedText='一般说明。',sourceLocator='p2',pageNumber=2,
                    riskCategory='文字重复',rationale='段落有重复，请核对。')
            return result
    gateway=TextQuality()
    with TestClient(create_app(Settings(),model_gateway=gateway)) as client:
        response=client.post('/ai/v1/agent/procurement-tender-compliance',json=body())
    assert response.status_code==200,response.text
    assert not any(s.get('reviewFocus')=='COUNTEREVIDENCE_REVIEW' for s in gateway.sources)
    assert all(f['riskLevel']=='WARNING' for f in response.json()['data']['findings'])
