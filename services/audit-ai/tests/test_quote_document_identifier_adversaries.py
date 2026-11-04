"""Adversarial scope tests for identifier/document monetary classification."""
import sys

sys.dont_write_bytecode = True

import pytest

from audit_ai.model import router as R


SAFE_BOUNDED_IDENTIFIER_CASES = [
    ('编号核对', '编号与报价表编号一致。'),
    ('序号核对', '序号和报价单序号一致。'),
    ('编码核对', '编码对应报价表内容相同。'),
    ('行号核对', '行号对照报价单编号一致。'),
    ('代码核对', '代码比对报价表编号一致。'),
    ('标识核对', '标识与报价单内容一致，数量和金额列另行核对。'),
    ('编号与报价表编号一致', '序号与报价单序号一致，数量及金额列可见。'),
]


@pytest.mark.parametrize('title,description', SAFE_BOUNDED_IDENTIFIER_CASES,
    ids=['number-table', 'sequence-sheet-and', 'code-corresponds', 'row-reference',
         'code-compare', 'identifier-later-amount-only', 'two-identifier-observations'])
def test_bounded_identifier_topic_and_document_name_can_be_compared_without_money(title, description):
    # A finite document-name role must cover each explicitly approved connector
    # without dropping the later nonconclusive material-field mention.
    assert R.ModelApplicationService._monetary_rejection_reason(title + '\n' + description) is None


UNSAFE_LITERAL_ADVERSARIES = [
    ('编号核对', '报价一致。'),
    ('编号核对', '报价表一致。'),
    ('编号已核对', '报价单与合同金额一致。'),
    ('编号与报价表一致', '报价单与中标金额一致。'),
    ('编号与报价表一致', '报价一致。'),
    ('编号核对', '编号已核对，报价表金额一致。'),
    ('编号核对', '编号已核对；报价表单价一致。'),
    ('编号核对', '编号已核对。报价表实际价格一致。'),
    ('编号核对', '编号已核对\n报价单金额一致。'),
    ('编号核对', '编号与报价表单价一致。'),
    ('编号核对', '编号和报价单金额一致。'),
    ('编号核对', '编号对应报价表实际价格一致。'),
    ('编号核对', '编号与报价表中的金额一致。'),
    ('编号核对', '编号与报价表编号一致且实际报价高于合同金额。'),
    ('编号核对', '编号与报价表编号一致但合同金额与中标金额不一致。'),
    ('编号核对', '编号与报价表编号一致而单价高于预算。'),
    ('编号核对', '编号与报价表编号一致且报价表金额相等。'),
    ('编号核对', '编号与报价表编号一致但报价单实际价格相同。'),
    ('编号核对', '编号与实际价格低于预算的报价表编号一致。'),
    ('编号核对', '编号与报价等于合同金额的报价表编号一致。'),
    ('编号核对', '编号与金额为900元的报价单编号一致。'),
    ('编号核对', '编号与单价一致的报价表内容一致。'),
    ('编号核对', '金额编号与报价表编号一致。'),
    ('编号核对', '单价代码和报价单编号一致。'),
    ('编号核对', '报价代码与报价表编号一致。'),
    ('编号核对', '编号与报价一致。'),
    ('编号核对', '序号对应实际报价一致。'),
    ('编号核对', '代码和实际报价相同。'),
    ('编号核对', '编号与报价表编号3×120一致。'),
    ('编号核对', '编号与报价单编号100元一致。'),
    ('编号核对', '编号与报价表编号一致，合同金额按3×120计算。'),
    ('编号核对', '编号与报价单编号一致，合同金额的3%高于中标金额的2%。'),
    ('编号核对', '编号与报价表编号一致，序号与报价单序号一致，二者金额相等。'),
    ('编号核对', '编号与报价表编号一致，序号与报价单序号一致，实际报价相同。'),
    ('编号核对', '编号与报价表编号一致，序号与报价单序号一致，报价表单价与报价单单价一致。'),
    ('编号核对', '编号与报价表编号一致，代码与报价单编号一致，合同金额与中标金额，二者一致。'),
    ('编号核对', '编号与报价表编号一致，报价表内容一致。'),
]


@pytest.mark.parametrize('title,description', UNSAFE_LITERAL_ADVERSARIES,
    ids=['identifier-title-not-quote', 'identifier-title-not-document', 'identifier-title-money',
         'safe-title-independent-quote-document', 'safe-title-independent-quote',
         'comma-does-not-share-topic', 'semicolon-does-not-share-topic',
         'period-does-not-share-topic', 'newline-does-not-share-topic',
         'document-unit-price', 'document-amount', 'document-actual-price', 'document-amount-attribute',
         'same-clause-second-quote-judgment', 'same-clause-second-money-judgment',
         'same-clause-second-unit-price', 'same-clause-second-document-amount',
         'same-clause-second-document-price', 'interposed-price-judgment', 'interposed-quote-judgment',
         'interposed-absolute-money', 'interposed-unit-price', 'money-word-in-identifier-topic',
         'unit-price-in-identifier-topic', 'bare-quote-in-identifier-topic',
         'no-document-suffix', 'no-document-suffix-actual', 'no-document-suffix-same',
         'original-arithmetic-context-unchanged', 'original-absolute-money-unchanged',
         'later-arithmetic-unchanged', 'later-rate-comparison-unchanged',
         'two-safe-then-anaphoric-amount', 'two-safe-then-quote-same',
         'two-safe-then-document-prices', 'two-safe-then-cross-clause-comparison',
         'previous-clause-identifier-not-document-waiver'])
def test_identifier_or_document_name_mask_cannot_lend_its_scope_to_financial_claims(title, description):
    # A safe substring cannot waive a second judgment; only its two document-name
    # characters may be masked. Arithmetic/absolute/rate gates see original text.
    assert R.ModelApplicationService._monetary_rejection_reason(title + '\n' + description) in {
        'MONETARY_VALUE', 'MONETARY_CALCULATION'}


@pytest.mark.parametrize('description', [
    '编号与报价表编号一致且总额为100。',
    '编号与报价表编号一致但税额高于实缴税额。',
    '编号与报价表一致且运费高于保险费。',
    '总额为100且编号与报价表编号一致。',
    '税额高于实缴税额但编号与报价表编号一致。',
    '运费高于保险费且编号与报价表一致。',
    '编号与报价表编号一致且价税合计相同。',
    '价税合计相同且编号与报价表编号一致。',
    '编号必须与分项报价表保持一致且总额为100。',
    '金额编号必须与分项报价表保持一致。',
    '编号必须与分项报价表单价保持一致。',
], ids=['later-total', 'later-tax', 'later-freight',
        'earlier-total', 'earlier-tax', 'earlier-freight',
        'later-price-tax-total', 'earlier-price-tax-total',
        'itemized-later-total', 'itemized-monetary-identifier', 'itemized-unit-price'])
def test_identifier_comparison_cannot_hide_another_relation_with_unlisted_money_words(description):
    # Retain the original quote-context guard even for financial nouns that are
    # not enumerated in _MONETARY_CONTEXT; prefix and suffix get no blanket waiver.
    assert R.ModelApplicationService._monetary_rejection_reason(description) == 'MONETARY_CALCULATION'
