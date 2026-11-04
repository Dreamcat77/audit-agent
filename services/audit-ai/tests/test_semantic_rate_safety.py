"""Rate-basis roles are not absolute money comparisons; safety still fails closed."""
import pytest

from audit_ai.config import Settings
from audit_ai.model.router import ModelApplicationService
from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment


@pytest.mark.parametrize('description', [
    '按合同金额的3%计算违约金，累计违约金上限为金额的12%。',
    '乙方未按期完成交付时按合同金额的3%/周计算违约金，累计违约金上限为合同金额的12%。',
    '乙方未按期完成交付时按合同金额的3%/周计算违约金，累计违约金上限为金额的12%。',
    '合同约定乙方延期交付每满一周按合同金额3%计算违约金，累计上限为合同总金额12%。',
    '合同约定乙方发生重大质量缺陷时按合同金额的18%一次性支付赔偿金。',
    '按合同价的18%作为约定违约责任承担。',
    '按合同价款18%作为约定违约责任比例。',
])
def test_triggered_rate_and_anaphoric_rate_cap_remain_evidence_grounded(description):
    # Break: scanning two different money words across safe rate clauses or
    # treating the event prefix as absolute monetary arithmetic drops real risk.
    assert ModelApplicationService._monetary_rejection_reason(description) is None
    catalog = EvidenceCatalog([EvidenceSegment('C1', 'body', 'CONTRACT_BODY', 0,
        len(description), description, True)])
    payload = {'summary': '核对违约比例及其适用条件。', 'hints': [{
        'category': 'BREACH_LIABILITY', 'attention': 'REVIEW', 'title': '延期违约责任需核对',
        'description': description, 'problemEvidenceIds': ['C1'], 'comparisonEvidenceIds': [],
        'unavailableMaterialCategory': None}]}
    service = ModelApplicationService(Settings(model_name='offline'), object())
    result, filtered, repairs, _ = service._normalize_contract_result(payload, {},
        {'CONTRACT_BODY'}, catalog, {'C1'}, operation_key='offline-rate',
        request_id='offline-rate', strict_machine_contract=True)
    assert filtered == 0 and not repairs
    assert len(result.hints) == 1
    assert result.hints[0].description == description
    assert result.hints[0].attention == 'REVIEW'
    assert list(result.hints[0].problem_evidence_ids) == ['C1']


@pytest.mark.parametrize('description', [
    '合同金额与中标金额，二者一致。',
    '合同金额的3%高于中标价。',
    '合同金额的3%与中标金额的12%相等。',
    '按合同金额的3%计算违约金，与中标金额，二者一致。',
    '按合同金额的3%计算违约金，合同金额低于预算。',
    '乙方未按期完成交付时按合同金额的3%/周计算违约金，金额为900元。',
    '合同金额由3×1200计算得到，违约金比例3%。',
    '预算高于合同金额时按合同金额的3%计算违约金。',
    '合同金额为9000，按合同金额的3%计算违约金。',
    '累计违约金上限为金额的12%，实际赔偿金额等于成交金额。',
    '累计违约金上限为金额的12%，实际赔偿金额大于成交金额。',
    '累计违约金上限为金额的12%，实际赔偿金额小于成交金额。',
    '合同总金额等于中标金额。',
    '合同总金额为9000。',
    '合同约定乙方延期交付每满一周按合同金额3%计算违约金，与中标金额，二者一致。',
    '按合同价的18%作为约定违约责任承担，实际金额为900元。',
    '按合同价的18%作为约定违约责任承担，与中标金额，二者相等。',
    '合同价款为9000。',
    '合同价款低于预算。',
    '按合同价款18%作为约定违约责任比例，与中标金额，二者一致。',
    '按合同金额的3%计算违约金，按中标金额的3%计算违约金，二者一致。',
    '按合同金额的3%计算违约金，按中标金额的3%计算违约金，二者不一致。',
    '按合同金额的3%计算违约金，按中标金额的3%计算违约金，前者大于后者。',
])
def test_rate_words_never_hide_absolute_money_or_comparisons(description):
    assert ModelApplicationService._monetary_rejection_reason(description) in {
        'MONETARY_VALUE', 'MONETARY_CALCULATION'}
