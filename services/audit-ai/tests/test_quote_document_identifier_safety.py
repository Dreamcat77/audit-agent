"""Identifier/document comparison regressions; all examples are synthetic literals."""
import sys

sys.dont_write_bytecode = True

import pytest

from audit_ai.model import router as R
from audit_ai.model.evidence import EvidenceCatalog, EvidenceSegment


SAFE_IDENTIFIER_OBSERVATIONS = [
    ('编号对照', '合同条目编号与报价表编号一致。'),
    ('序号对照', '合同明细序号与报价单序号相同。'),
    ('行号对照', '本块清单行号与报价表行号一致。'),
    ('清单编号核对', '本块合同条目编号与报价表一致，逐项核对数量及金额。'),
    ('本块中编号与报价表一致性核对',
        '当前片段可见明细的行编号均与对照的报价表内容一致，涵盖了行内的数量及金额。'),
    ('合同条目编号核对', '当前片段序号与报价单编号相同，金额字段另行逐项核对。'),
    ('序号与报价单一致性核对', '序号与报价单一致，金额列仅供表头列示。'),
    ('编号对照使用报价表', '编号核对使用报价表，数量和金额列可见。'),
    ('清单编号核对', '本块清单编号必须与分项报价表保持一致，逐行核对数量及金额。'),
    ('序号要求', '序号必须与分项报价单编号相同。'),
]


@pytest.mark.parametrize('title,description', SAFE_IDENTIFIER_OBSERVATIONS,
    ids=['table-number', 'sheet-sequence', 'table-row-id', 'table-number-later-amount',
         'safe-receipt-24-11-shape', 'title-newline-later-amount', 'sheet-later-amount-header',
         'reference-without-financial-conclusion', 'required-itemized-table-grammar',
         'required-itemized-sheet-grammar'])
def test_explicit_identifier_document_comparison_is_not_a_monetary_verdict_and_keeps_all_evidence(title, description):
    # Break caught: treating 报价 inside 报价表/报价单 as an amount field, then
    # pairing it across clauses with a later nonconclusive mention of 金额.
    text = title + '\n' + description
    assert R.ModelApplicationService._monetary_rejection_reason(text) is None
    bid = '报价表列示行号A1和数量、金额字段。'
    catalog = EvidenceCatalog([
        EvidenceSegment('C1', 'contract', 'CONTRACT_BODY', 0, len(description), description, True),
        EvidenceSegment('B1', 'bid', 'BID_RESPONSE', 0, len(bid), bid, True),
    ])
    payload = {'summary': '核对本块编号与明细字段。', 'hints': [{
        'category': 'CONTRACT_STRUCTURE', 'attention': 'INFO', 'title': title,
        'description': description, 'problemEvidenceIds': [],
        'comparisonEvidenceIds': ['C1', 'B1'], 'unavailableMaterialCategory': None}]}
    result, filtered, repairs, _ = R.ModelApplicationService._normalize_contract_result(
        payload, {}, {'CONTRACT_BODY', 'BID_RESPONSE'}, catalog, {'C1', 'B1'},
        operation_key='A9', request_id='offline-quote-identifier', strict_machine_contract=True)
    assert filtered == 0 and repairs == () and len(result.hints) == 1
    hint = result.hints[0]
    assert (hint.category, hint.attention, hint.title, hint.description) == (
        'CONTRACT_STRUCTURE', 'INFO', title, description)
    assert hint.problem_evidence_ids == () and hint.comparison_evidence_ids == ('C1', 'B1')
    assert set(hint.evidence_refs) == {'contract', 'bid'}
    assert {(item.file_id, item.excerpt) for item in hint.evidence_items} == {
        ('contract', description), ('bid', bid)}


UNSAFE_MONETARY_CONCLUSIONS = [
    ('报价核对', '合同报价与中标金额一致。'),
    ('单价核对', '报价表单价与合同单价一致。'),
    ('金额核对', '报价表金额与合同金额相同。'),
    ('价格核对', '报价单中的价格与中标价一致。'),
    ('编号对照', '编号与报价表一致，合同金额与中标金额一致。'),
    ('编号对照', '编号与报价表一致；报价等于合同金额。'),
    ('编号与报价表一致', '报价为100元。'),
    ('编号对照', '本块合同条目编号与报价表编号一致，金额为1000。'),
    ('编号对照', '编号与报价单一致，金额按3×120计算。'),
    ('合同编号核对', '报价与中标金额一致。'),
    ('编号与报价表一致', '报价与预算相同。'),
    ('编号背景', '编号仅作背景，报价与合同金额一致。'),
    ('编号核对', '编号已核对，报价一致。'),
    ('编号核对', '报价表一致。'),
    ('报价表编号核对', '报价一致。'),
    ('编号对照', '编号与报价表一致，序号与报价单一致，合同金额等于成交金额。'),
    ('编号对照', '编号与报价表一致，序号与报价单一致，二者金额相等。'),
    ('编号对照', '编号与报价表一致，序号与报价单一致，实际报价高于合同金额。'),
    ('编号对照', '编号与报价表单价一致。'),
    ('序号对照', '序号与报价表金额相同。'),
    ('编号对照', '合同编号与报价一致。'),
    ('编号对照', '报价表编号一致，但报价与中标价不一致。'),
    ('编号对照', '编号与报价表一致，合同金额的3%高于中标价。'),
    ('编号对照', '编号与报价单一致，合同金额为9000。'),
    ('编号对照', '编号与报价表一致，合同金额与中标金额，二者一致。'),
    ('编号对照', '编号与报价表一致，报价表与报价单相同。'),
]


@pytest.mark.parametrize('title,description', UNSAFE_MONETARY_CONCLUSIONS,
    ids=['actual-quote', 'table-unit-price', 'table-amount', 'sheet-price',
         'safe-then-actual-money', 'safe-then-quote-semicolon', 'safe-title-absolute-money',
         'safe-then-labelled-amount', 'safe-then-arithmetic', 'identifier-only-in-title',
         'safe-title-independent-quote', 'fake-identifier-context', 'identifier-in-another-clause',
         'identifier-title-independent-document-conclusion', 'document-title-independent-quote',
         'two-safe-then-money-compare', 'two-safe-then-anaphoric-money', 'two-safe-then-actual-quote',
         'identifier-compares-to-unit-price', 'identifier-compares-to-amount', 'no-document-suffix',
         'document-identifier-then-actual-quote', 'safe-then-rate-comparison', 'safe-then-absolute-number',
         'safe-then-cross-clause-money-pair', 'identifier-in-prior-clause-not-global-waiver'])
def test_document_or_identifier_words_never_exempt_independent_financial_conclusions(title, description):
    # Break caught: a document-name exception must not erase real monetary
    # fields or lend an identifier from another clause/title to a quote verdict.
    assert R.ModelApplicationService._monetary_rejection_reason(title + '\n' + description) in {
        'MONETARY_VALUE', 'MONETARY_CALCULATION'}
