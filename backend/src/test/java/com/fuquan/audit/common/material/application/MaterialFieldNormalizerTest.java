package com.fuquan.audit.common.material.application;

import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaterialFieldNormalizerTest {
    @Test void croppedVerbatimAmountIsDroppedWhenItHasAnotherFinancialRole() {
        for(String label:List.of("合同金额","中标金额","最高限价")) {
            var source=List.of(new MaterialFieldNormalizer.Segment("p1",label+"：9000元；其他说明"));
            var fields=normalizer.normalize(result(new Fact("budgetAmount","9000元","p1","9000元")),source);
            assertThat(fields.budgetAmount()).isNull();
            assertThat(fields.evidence()).doesNotContainKey("budgetAmount");
        }
    }
    @Test void explicitDepartmentIsNotAcceptedAsApplicantEvenIfTheExcerptIsTrue() {
        var fields=normalizer.normalize(result(new Fact("applicantName","信息中心","p1","信息中心")),
            List.of(new MaterialFieldNormalizer.Segment("p1","申请部门：信息中心")));
        assertThat(fields.applicantName()).isNull();
        assertThat(fields.evidence()).doesNotContainKey("applicantName");
    }
    @Test void financialUppercaseAndArabicEvidenceNormalizeToTheSameAmount() {
        var source=List.of(new MaterialFieldNormalizer.Segment("p","预算金额：人民币壹拾贰万叁仟肆佰伍拾陆元柒角捌分；预算金额：123456.78元"));
        var fields=normalizer.normalize(result(),source);
        assertThat(fields.budgetAmount()).isEqualByComparingTo("123456.78");
        assertThat(fields.conflicts()).isEmpty();
    }
    @Test void conflictingUppercaseCannotBeHiddenByModelReturningOnlyTheArabicSubstring() {
        String source="预算金额：120000元（大写：壹拾万元整）";
        var fields=normalizer.normalize(new Result(List.of(new Row("one",List.of(new Fact("budgetAmount","120000元","p",source)))),"m","v"),
            List.of(new MaterialFieldNormalizer.Segment("p",source)));
        assertThat(fields.budgetAmount()).isNull();assertThat(fields.conflicts()).contains("budgetAmount");
    }
    private final MaterialFieldNormalizer normalizer = new MaterialFieldNormalizer();
    private static final String TEXT = "项目名称：设备更新；预算金额：12万元；预算金额：120,000.00元；申请部门：信息中心；申请部门：办公室";
    private final List<MaterialFieldNormalizer.Segment> segments = List.of(new MaterialFieldNormalizer.Segment("p1", TEXT));
    private static Fact fact(String field, String value) { return new Fact(field, value, "p1", TEXT); }
    private static Result result(Fact... facts) { return new Result(List.of(new Row("one", List.of(facts))), "model", "v1"); }
    @Test void retainsEvidenceAndNormalizesMoneyWithoutTreatingEquivalentValuesAsConflict() {
        var fields = normalizer.normalize(result(fact("projectName", "设备更新"), fact("budgetAmount", "12万元"),
                fact("budgetAmount", "120,000.00元")), segments);
        assertThat(fields.budgetAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));
        assertThat(fields.conflicts()).containsExactly("departmentName");
        assertThat(fields.evidence().get("budgetAmount")).hasSize(2);
        assertThat(fields.applicantName()).isNull();
        assertThat(fields.aiModel()).isEqualTo("model");
    }
    @Test void conflictingDepartmentStaysEmpty() {
        var fields = normalizer.normalize(result(fact("departmentName", "信息中心"), fact("departmentName", "办公室")), segments);
        assertThat(fields.departmentName()).isNull();
        assertThat(fields.conflicts()).containsExactly("departmentName");
    }
    @Test void dropsHallucinatedValueLocatorAndForeignFieldCandidates() {
        var source = List.of(new MaterialFieldNormalizer.Segment("p", "普通说明文本"));
        for (Fact invalid : List.of(new Fact("projectName", "不存在", "p", "普通说明文本"),
                new Fact("projectName", "设备更新", "missing", TEXT),
                new Fact("supplierName", "信息中心", "p", "普通说明文本"))) {
            var fields=normalizer.normalize(result(invalid), source);
            assertThat(fields.projectName()).isNull();
            assertThat(fields.evidence()).isEmpty();
        }
    }
    @Test void rejectsMultipleProjectsAndDuplicateLocators() {
        var different = List.of(new MaterialFieldNormalizer.Segment("one", "项目名称：设备更新"),
                new MaterialFieldNormalizer.Segment("two", "项目名称：其他项目"));
        assertThatThrownBy(() -> normalizer.normalize(new Result(List.of(
                new Row("1",List.of(new Fact("projectName","设备更新","one","项目名称：设备更新"))),
                new Row("2",List.of(new Fact("projectName","其他项目","two","项目名称：其他项目")))),"m","v"),different))
                .isInstanceOf(MaterialFieldNormalizer.MultipleProjectMaterial.class);
        assertThatThrownBy(() -> normalizer.normalize(result(), List.of(segments.get(0), segments.get(0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void dropsOneUntrustedFactButKeepsOtherGroundedFacts() {
        var source = List.of(new MaterialFieldNormalizer.Segment("p",
                "项目名称：设备更新；投标报价：9000元"));
        var result = new MaterialFieldAiPort.Result(List.of(new MaterialFieldAiPort.Row("r", List.of(
                new MaterialFieldAiPort.Fact("projectName", "设备更新", "p", "项目名称：设备更新"),
                new MaterialFieldAiPort.Fact("budgetAmount", "9000", "p", "投标报价：9000元")))), "model", "v1");
        var fields = normalizer.normalize(result, source);
        assertThat(fields.projectName()).isEqualTo("设备更新");
        assertThat(fields.budgetAmount()).isNull();
        assertThat(fields.evidence()).containsOnlyKeys("projectName");
    }
    @Test void modelLocalRowKeysDoNotSplitSupplementaryFactsAndScalarConflictsStayManual() {
        var source=List.of(new MaterialFieldNormalizer.Segment("one","信息中心 12万元"),
                new MaterialFieldNormalizer.Segment("two","办公室 13万元"));
        var fields=normalizer.normalize(new Result(List.of(
                new Row("chunk-A",List.of(new Fact("departmentName","信息中心","one","信息中心 12万元"),
                        new Fact("budgetAmount","12万元","one","信息中心 12万元"))),
                new Row("chunk-B",List.of(new Fact("departmentName","办公室","two","办公室 13万元"),
                        new Fact("budgetAmount","13万元","two","办公室 13万元")))),"m","v"),source);
        assertThat(fields.budgetAmount()).isNull(); assertThat(fields.departmentName()).isNull();
        assertThat(fields.conflicts()).contains("budgetAmount","departmentName");
        assertThat(fields.evidence().get("budgetAmount")).hasSize(2);
    }
    @Test void malformedCommaAndZeroAmountsAreNotAutofilled() {
        for (String raw : List.of("1,2元", "0元", "99999999999999999元")) {
            var fields = normalizer.normalize(new Result(List.of(new Row("one", List.of(new Fact("budgetAmount", raw, "p", raw)))), "m", "v"),
                    List.of(new MaterialFieldNormalizer.Segment("p", raw)));
            assertThat(fields.budgetAmount()).isNull();
            assertThat(fields.conflicts()).contains("budgetAmount");
        }
    }
    @Test void fillsAllExplicitLabelsAndMultiParagraphTechnicalRequirementsDespiteAiOmissions() {
        var source = List.of(
                new MaterialFieldNormalizer.Segment("p1", "采购项目名称：设备更新 | 申请部门：信息中心 | 采购类型：货物"),
                new MaterialFieldNormalizer.Segment("p2", "预算科目编码：M01；预算科目名称：设备购置；申请人：张三；采购方式：询价"),
                new MaterialFieldNormalizer.Segment("p3", "三、技术要求"),
                new MaterialFieldNormalizer.Segment("p4", "1. 服务器支持双电源"),
                new MaterialFieldNormalizer.Segment("p5", "2. 三年质保"),
                new MaterialFieldNormalizer.Segment("p6", "四、评分标准"),
                new MaterialFieldNormalizer.Segment("p7", "价格分40分"));
        var fields = normalizer.normalize(result(), source);
        assertThat(fields.projectName()).isEqualTo("设备更新");
        assertThat(fields.departmentName()).isEqualTo("信息中心");
        assertThat(fields.procurementType()).isEqualTo("货物");
        assertThat(fields.subjectCode()).isEqualTo("M01");
        assertThat(fields.subjectName()).isEqualTo("设备购置");
        assertThat(fields.applicantName()).isEqualTo("张三");
        assertThat(fields.procurementMethod()).isEqualTo("询价");
        assertThat(fields.requirementDescription()).contains("双电源", "三年质保").doesNotContain("价格分");
        assertThat(fields.conflicts()).isEmpty();
        fields.evidence().values().stream().flatMap(List::stream).forEach(e ->
                assertThat(source.stream().filter(s -> s.locator().equals(e.locator())).findFirst().orElseThrow().text()).contains(e.excerpt()));
    }
    @Test void purchaserAndTenderTitleNeverBecomeApplicantDepartmentOrProcurementMethod() {
        var fields = normalizer.normalize(result(), List.of(new MaterialFieldNormalizer.Segment("p", "XX单位\n设备采购项目招标文件\n采购单位：XX单位\n联系人：李四")));
        assertThat(fields.departmentName()).isNull();
        assertThat(fields.applicantName()).isNull();
        assertThat(fields.procurementMethod()).isNull();
    }
    @Test void numberedRequirementsKeepAllSourceEvidenceBeyondTheFormFieldLimit() {
        var source = java.util.stream.IntStream.rangeClosed(1, 120)
                .mapToObj(i -> new MaterialFieldNormalizer.Segment("p" + i,
                        "第" + i + "条技术要求：独立验收项" + i + "必须核对设备并保存完整验收记录。"))
                .toList();
        var fields = normalizer.normalize(result(), source);
        assertThat(fields.evidence().get("requirementDescription")).hasSize(120);
        assertThat(fields.evidence().get("requirementDescription").get(119).value()).contains("120");
        assertThat(fields.requirementDescription()).startsWith("以下为部分原文摘录，完整需求以所选材料为准：").hasSizeLessThan(1000);
        assertThat(fields.conflicts()).doesNotContain("requirementDescription");
    }
}
