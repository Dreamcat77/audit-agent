"""Synthetic contract text: document identifiers never decide business money."""
import unittest
import sys

from audit_ai.model.router import ModelApplicationService


class DocumentIdentifierMoneyBoundary(unittest.TestCase):
    def test_explicit_identifier_consistency_title_with_later_f02_deferral(self):
        text = ("报价表条目编号一致性核对\n"
                "本块仅核对报价表条目编号；成交金额、合同金额由F02另行核验。")
        self.assertIsNone(ModelApplicationService._monetary_rejection_reason(text))

    def test_document_then_explicit_identifier_has_the_same_boundary_as_reverse_order(self):
        for field in ("编号", "序号", "编码", "行号", "代码", "标识"):
            with self.subTest(field=field):
                self.assertIsNone(ModelApplicationService._monetary_rejection_reason(
                    f"报价表条目{field}一致；成交金额及合同金额另由F02核验。"))
                self.assertIsNone(ModelApplicationService._monetary_rejection_reason(
                    f"{field}与报价表{field}一致；成交金额及合同金额另由F02核验。"))

    def test_ambiguous_document_or_item_consistency_is_not_a_price_waiver(self):
        for text in ("报价一致", "报价表一致性核对", "报价表条目一致性说明",
                     "报价表条目一致；成交金额及合同金额由F02另行核验。"):
            with self.subTest(text=text):
                self.assertEqual("MONETARY_CALCULATION", ModelApplicationService._monetary_rejection_reason(text))

    def test_actual_money_relation_is_not_excused_by_f02_or_document_identifiers(self):
        for unsafe in ("成交金额与合同金额一致。", "成交金额、合同金额，二者一致。",
                       "报价与成交金额一致。", "合同金额高于中标金额。",
                       "成交金额和合同金额由F02核验；经核对二者一致。",
                       "合同金额较中标金额高2%。"):
            for text in ("报价表条目编号一致；" + unsafe,
                         unsafe + "报价表条目编号一致。",
                         unsafe + "\n本块仅核对报价表条目编号。"):
                with self.subTest(text=text):
                    self.assertIsNotNone(ModelApplicationService._monetary_rejection_reason(text))

    def test_identifier_does_not_hide_absolute_values_arithmetic_or_unknown_financial_nouns(self):
        for text in ("报价表编号一致；合同金额300元。", "报价表编号一致；合同金额按3×7计算。",
                     "报价表编号一致且运费一致。", "运费一致且报价表编号一致。",
                     "报价表编号一致且单价一致。", "报价表编号一致且合同金额一致。"):
            with self.subTest(text=text):
                self.assertIsNotNone(ModelApplicationService._monetary_rejection_reason(text))


if __name__ == "__main__":
    unittest.main(testRunner=unittest.TextTestRunner(stream=sys.stdout, verbosity=2))
