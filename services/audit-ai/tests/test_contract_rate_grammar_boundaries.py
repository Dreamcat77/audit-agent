"""Literal liability rates stay intact; attribution never waives monetary safety."""
import pytest

from audit_ai.config import Settings
from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment
from audit_ai.model.router import ModelApplicationService


@pytest.mark.parametrize('description', [
    '约定违约责任比例为合同金额的18%。',
    '约定违约责任比例为合同价款的18%。',
    '违约责任比例为合同金额的18%。',
    '合同约定违约责任比例为合同价款的18%。',
    '乙方延期交付时应按合同金额的3%计算违约金，累计违约金上限为金额的12%。',
    '乙方延期交付时应当按合同金额的3%计算违约金，累计违约金上限为金额的12%。',
])
def test_literal_rate_attribution_and_modal_do_not_become_money_calculation(description):
    # Break: a real rate basis is rejected merely for its nominal/modal prefix.
    assert ModelApplicationService._monetary_rejection_reason(description) is None
    catalog = EvidenceCatalog([EvidenceSegment('C1', 'body', 'CONTRACT_BODY', 0,
        len(description), description, True)])
    payload = {'summary': '核对条款比例及触发条件。', 'hints': [{
        'category': 'BREACH_LIABILITY', 'attention': 'REVIEW', 'title': '违约比例需核对',
        'description': description, 'problemEvidenceIds': ['C1'], 'comparisonEvidenceIds': [],
        'unavailableMaterialCategory': None}]}
    result, rejected, repairs, _ = ModelApplicationService(Settings(model_name='offline'), object())._normalize_contract_result(
        payload, {}, {'CONTRACT_BODY'}, catalog, {'C1'}, operation_key='rate-grammar-offline',
        request_id='rate-grammar-offline', strict_machine_contract=True)
    assert rejected == 0 and not repairs and len(result.hints) == 1
    assert result.hints[0].description == description
    assert result.hints[0].attention == 'REVIEW'
    assert tuple(result.hints[0].problem_evidence_ids) == ('C1',)


@pytest.mark.parametrize('description', [
    '约定违约责任比例为合同金额的18%，实际违约金为900元。',
    '约定违约责任比例为合同金额的18%，合同金额高于中标金额。',
    '约定违约责任比例为合同金额的18%，合同金额与预算金额一致。',
    '约定违约责任比例为合同金额的18%，按中标金额的3%计算违约金，二者一致。',
    '约定违约责任比例为合同金额的18%，按中标金额的3%计算违约金，前者大于后者。',
    '合同金额为9000，约定违约责任比例为合同金额的18%。',
    '合同金额由3×1200计算得到，约定违约责任比例为18%。',
    '约定合同金额等于中标金额。',
    '乙方延期交付时应按合同金额的3%计算违约金，税费与报价一致。',
])
def test_rate_grammar_does_not_waive_other_monetary_judgments(description):
    assert ModelApplicationService._monetary_rejection_reason(description) in {
        'MONETARY_VALUE', 'MONETARY_CALCULATION'}
