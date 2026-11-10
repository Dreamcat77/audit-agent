"""Shared Java/Python conformance and real HTTP privacy boundary regression."""
import json
from pathlib import Path
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient

from audit_ai.client_party import party, variants
from audit_ai.client_privacy import ClientIdentity, HEADER
from audit_ai.config import Settings
from audit_ai.main import create_app
from audit_ai.model.gateway import OpenAiCompatibleModelGateway
from test_client_privacy import header


DATA = json.loads((Path(__file__).parent / 'fixtures/client_party_cases.json').read_text(encoding='utf-8'))


@pytest.mark.parametrize('case', DATA['cases'], ids=lambda case: case['id'])
def test_role_conformance(case):
    assert party(case['text'], DATA['names']) == case['expected']


@pytest.mark.parametrize('case', [c for c in DATA['cases'] if c['expected'] != 'UNMATCHED'], ids=lambda case: case['id'])
def test_all_accepted_variants_are_masked_and_exactly_reversible(case):
    original = case['text']
    boundary = ClientIdentity(tuple(DATA['names']), ()).boundary(
        {'messages': [{'content': 'JSON'}, {'content': original}]})
    masked = boundary.body['messages'][1]['content']
    assert masked != original and boundary.reverse
    assert len(masked) == len(original)
    assert len(masked.encode('utf-16-le')) == len(original.encode('utf-16-le'))
    assert boundary.restore(masked) == original
    assert all(name not in masked for name in DATA['names'])


def test_expansion_requires_configured_department_not_arbitrary_child_unit():
    assert '北辰市公安局警务保障处' not in variants(['北辰市公安局'])
    assert '北辰市公安局警务保障处' in variants(DATA['names'])
    assert party('甲方：北辰市公安局警务保障处', ['北辰市公安局']) == 'UNMATCHED'


def test_unconfigured_short_alias_does_not_cross_documents():
    assert party('甲方：北辰市公安局（以下简称本单位）', DATA['names']) == 'PARTY_A'
    assert party('甲方：本单位', DATA['names']) == 'UNMATCHED'


@pytest.mark.parametrize('label,role,explicit', [('委托人（乙方）', 'PARTY_B', False),
    ('采购人', 'PURCHASER', False), ('采购人', 'PURCHASER', True)])
def test_functional_label_and_expanded_name_use_the_same_http_privacy_policy(label, role, explicit):
    name = '北辰市公安局（警务保障处）'
    text = f'{label}：{name}\n合同约定由乙方向甲方支付费用。'
    document = str(uuid4())
    requests = []
    def provider(request):
        body = json.loads(request.content)
        source = json.loads(body['messages'][1]['content'])['documents'][0]['text']
        assert all(n not in request.content.decode() for n in DATA['names'])
        assert '警务保障处' not in source and name not in source
        assert role in body['messages'][0]['content']
        requests.append(1)
        content = {'role': 'CONTRACT', 'profile': 'SERVICE', 'complete': True,
            'facts': [{'type': 'PARTY_B', 'value': source.split('：', 1)[1].split('\n')[0],
                'citations': [{'document': document, 'text': source.split('\n')[0]}]}],
            'clauses': [], 'findings': []}
        return httpx.Response(200, json={'model': 'fixture', 'choices': [{'finish_reason': 'stop',
            'message': {'content': json.dumps(content, ensure_ascii=False)}}]})
    settings = Settings(model_base_url='https://provider.invalid/v1', model_api_key='fixture',
        model_name='fixture', client_identity_required=True)
    gateway = OpenAiCompatibleModelGateway(settings, transport=httpx.MockTransport(provider))
    with TestClient(create_app(settings=settings, model_gateway=gateway)) as client:
        response = client.post('/ai/v1/contracts/analyze-case', headers={HEADER: header(DATA['names'])},
            json={'caseId': str(uuid4()), 'inputDigest': 'a' * 64, 'scope': 'FRAGMENT',
                  'documents': [{'id': document, 'text': text}],
                  **({'reviewParties': {document: role}} if explicit else {})})
    assert response.status_code == 200
    assert response.json()['facts'][0]['value'] == name
    assert response.json()['facts'][0]['citations'][0]['text'] == text.split('\n')[0]
    assert len(requests) == 1


def test_document_declared_alias_is_masked_and_exactly_restored():
    text = '委托方：北辰市公安局（以下简称“本局”）\n乙方：本局'
    boundary = ClientIdentity(tuple(DATA['names']), ()).boundary(
        {'messages': [{'content': 'JSON'}, {'content': text}]})
    masked = boundary.body['messages'][1]['content']
    assert '本局' not in masked and '北辰市公安局' not in masked
    assert boundary.restore(masked) == text
    assert len(masked) == len(text)


def test_internal_header_does_not_spread_a_local_alias_to_another_document():
    first, second = str(uuid4()), str(uuid4())
    identity = ClientIdentity.from_header(header(DATA['names']), {'documents': [
        {'id': first, 'text': '委托方：北辰市公安局（以下简称“本局”）\n乙方：本局'},
        {'id': second, 'text': '甲方：本局'}]})
    assert dict(identity.parties) == {first: 'PARTY_B', second: 'UNMATCHED'}
