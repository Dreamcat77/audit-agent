package com.fuquan.audit.contract.application.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContractAiTextRedactorTest {

    @Test
    void removesBareTenThousandYuanAmountInMonetaryContext() {
        String redacted = new ContractAiTextRedactor().redact("预算口径12.5万元");

        assertThat(redacted).doesNotContain("预算口径", "12.5万元");
        assertThat(redacted).contains(ContractAiTextRedactor.MASK);
    }

    @Test
    void removesMultipleMonetaryFormsInOneLine() {
        String redacted = new ContractAiTextRedactor().redact("参考报价：￥5,000/台、5,000.00元/台");

        assertThat(redacted).doesNotContain("￥5,000/台", "5,000.00元/台", "报价");
    }

    @Test
    void removesYuanPerUnitAmount() {
        assertThat(new ContractAiTextRedactor().redact("含税单价5,000.00元/套"))
                .doesNotContain("含税单价", "5,000.00元/套");
    }

    @Test
    void removesTenThousandYuanPerItemAmount() {
        assertThat(new ContractAiTextRedactor().redact("最高限价12.5万元/项"))
                .doesNotContain("最高限价", "12.5万元/项");
    }

    @Test
    void recognizesTaxInclusiveUnitPriceHeader() {
        assertPipeMonetaryHeaderRedacted("含税单价（元）");
    }

    @Test
    void recognizesTaxExclusiveUnitPriceHeader() {
        assertPipeMonetaryHeaderRedacted("不含税单价（元）");
    }

    @Test
    void recognizesSubtotalAndTaxHeaders() {
        String source = "名称 | 数量 | 小计（元） | 税额（元）\n台式计算机 | 100 | 500000.00 | 65000.00";

        String redacted = new ContractAiTextRedactor().redact(source);

        assertThat(redacted).contains("台式计算机", "100");
        assertThat(redacted).doesNotContain("小计（元）", "税额（元）", "500000.00", "65000.00");
    }

    @Test
    void recognizesHighestLimitHeader() {
        assertPipeMonetaryHeaderRedacted("最高限价（元）");
    }

    @Test
    void recognizesAdditionalProcurementMonetaryHeaders() {
        for (String header : new String[]{"成交价（元）", "合计金额（元）", "金额合计（元）", "含税总价（元）"}) {
            assertPipeMonetaryHeaderRedacted(header);
        }
    }

    @Test
    void handlesFlattenedTableWithTaxRateAndRemarkColumns() {
        String source = """
                标识
                含税单价（元）
                税率
                申报金额（元）
                备注
                品牌
                单位
                参数
                规格
                A
                5000.00
                13%
                650.00
                测试
                品牌A
                台
                16G
                i7
                """;

        String redacted = new ContractAiTextRedactor().redact(source);

        assertThat(redacted).contains("标识", "税率", "备注", "品牌", "单位", "参数", "规格",
                "A", "13%", "测试", "品牌A", "台", "16G", "i7");
        assertThat(redacted).doesNotContain("含税单价（元）", "申报金额（元）", "5000.00", "650.00");
    }

    @Test
    void removesPipeDelimitedMonetaryColumnsWithoutRemovingQuantityOrProductFacts() {
        String source = """
                标识 | 货物名称 | 数量 | 单价（元） | 申报金额（元）
                DESKTOP-01 | 台式计算机 | 100 | 5000.00 | 490000.00
                LAPTOP-01 | 便携式计算机 | 20 | 7000.00 | 140000.00
                """;

        String redacted = new ContractAiTextRedactor().redact(source);

        assertThat(redacted).contains(
                "DESKTOP-01", "LAPTOP-01", "台式计算机", "便携式计算机", "100", "20");
        assertThat(redacted).doesNotContain(
                "5000.00", "490000.00", "7000.00", "140000.00", "单价（元）", "申报金额（元）");
    }

    @Test
    void removesTabDelimitedMonetaryColumns() {
        String source = "货物名称\t数量\t单价（元）\t申报金额（元）\n"
                + "台式计算机\t100\t5000.00\t490000.00";

        String redacted = new ContractAiTextRedactor().redact(source);

        assertThat(redacted).contains("台式计算机", "100");
        assertThat(redacted).doesNotContain("5000.00", "490000.00", "单价（元）", "申报金额（元）");
    }

    @Test
    void removesMonetaryColumnsFromTheExistingDocxNewlineCellStream() {
        String source = """
                标识
                货物名称
                数量
                单价（元）
                申报金额（元）
                DESKTOP-01
                台式计算机
                100
                5000.00
                490000.00
                LAPTOP-01
                便携式计算机
                20
                7000.00
                140000.00
                """;

        String redacted = new ContractAiTextRedactor().redact(source);

        assertThat(redacted).contains(
                "DESKTOP-01", "LAPTOP-01", "台式计算机", "便携式计算机", "100", "20");
        assertThat(redacted).doesNotContain(
                "5000.00", "490000.00", "7000.00", "140000.00", "单价（元）", "申报金额（元）");
    }

    @Test
    void recognizesParenthesizedYuanAndTenThousandYuanMonetaryLabels() {
        String source = """
                金额(元)：101.00
                金额（元）：102.00
                合同金额（元）：103.00
                中标金额（元）：104.00
                单价（元）：105.00
                报价（万元）：106.00
                价格（元）：107.00
                费用(万元):108.00
                """;

        String redacted = new ContractAiTextRedactor().redact(source);

        assertThat(redacted).doesNotContain(
                "101.00", "102.00", "103.00", "104.00", "105.00", "106.00", "107.00", "108.00",
                "金额(元)", "金额（元）", "合同金额（元）", "中标金额（元）", "单价（元）", "报价（万元）",
                "价格（元）", "费用(万元)");
    }

    @Test
    void preservesQuantityDurationServiceHoursAndIdentifiers() {
        String source = """
                项目编号 CG-2026-002
                合同编号 HT-CG-2026-002
                统一社会信用代码 91310000TEST00001X
                20个工作日
                36个月
                7×24小时
                5×8小时
                数量100
                2026年08月31日
                """;

        assertThat(new ContractAiTextRedactor().redact(source)).isEqualTo(source);
    }

    @Test
    void masksAbsoluteMoneyButPreservesClauseRatesAndNonMonetaryThresholds() {
        String source = """
                合同金额：67000元。
                乙方每延期一周，按合同金额1%支付违约金；
                累计违约金最高不超过合同金额20%。
                延期超过1个月，甲方可解除合同。
                质保期3年，故障响应48小时。
                """;

        String redacted = new ContractAiTextRedactor().redact(source);

        assertThat(redacted).doesNotContain("67000", "67000元");
        assertThat(redacted).contains(
                "按合同金额1%支付违约金",
                "累计违约金最高不超过合同金额20%",
                "延期超过1个月",
                "质保期3年",
                "故障响应48小时");
    }

    @Test
    void masksDecoratedAndSpacedAbsoluteRmbWithoutRemovingClauseRates() {
        String source = """
                人民币【 67000.00】元
                人民币【67000】元
                人民币 67,000.00 元
                大写金额【陆万柒仟】元整
                每延期一周按合同金额1%支付违约金；
                特定违约责任为合同金额20%；
                维护费用按合同金额6%/年计算。
                """;

        String redacted = new ContractAiTextRedactor().redact(source);

        assertThat(redacted).doesNotContain(
                "67000.00", "【67000】", "67,000.00", "陆万柒仟");
        assertThat(redacted).contains(
                "合同金额1%", "合同金额20%", "合同金额6%/年");
    }

    @Test
    void removesWholeMonetaryFieldsWithoutRemovingSemanticBusinessFacts() {
        String source = """
                项目名称：数据中心办公设备采购项目
                项目编号：CG-2026-002
                合同编号：HT-2026-001
                供应商：测试科技有限公司
                统一社会信用代码：91310000TEST00001X
                签订日期：2026年8月31日
                交付期限：20个工作日
                质保期：36个月
                服务时间：7×24小时
                服务范围：设备安装和现场支持
                分包限制：未经采购人同意不得分包
                名称：台式计算机
                型号：Desk-Pro
                数量：100
                单价：5000.00
                申报金额：500000.00
                预算金额：700000.00
                合同金额：407000.00元
                金额大写：人民币肆拾万柒仟元整
                中标金额：640000元
                报价：￥510000
                """;

        String redacted = new ContractAiTextRedactor().redact(source);

        assertThat(redacted).doesNotContain(
                "5000.00", "500000.00", "700000.00", "407000.00", "人民币肆拾万柒仟元整",
                "640000", "510000", "单价", "申报金额", "预算金额", "合同金额", "金额大写", "中标金额", "报价");
        assertThat(redacted).contains(
                "数据中心办公设备采购项目", "CG-2026-002", "HT-2026-001", "测试科技有限公司",
                "91310000TEST00001X", "2026年8月31日", "20个工作日", "36个月", "7×24小时",
                "设备安装和现场支持", "未经采购人同意不得分包", "台式计算机", "Desk-Pro",
                "数量：100", ContractAiTextRedactor.MASK);
        assertThat(ContractAiTextRedactor.MASK).isEqualTo("[确定性数值字段已移除]");
    }

    private static void assertPipeMonetaryHeaderRedacted(String header) {
        String source = "名称 | 数量 | " + header + "\n台式计算机 | 100 | 5000.00";

        String redacted = new ContractAiTextRedactor().redact(source);

        assertThat(redacted).contains("台式计算机", "100");
        assertThat(redacted).doesNotContain(header, "5000.00");
    }
}
