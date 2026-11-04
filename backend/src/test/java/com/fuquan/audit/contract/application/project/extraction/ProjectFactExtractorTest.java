package com.fuquan.audit.contract.application.project.extraction;

import com.fuquan.audit.contract.application.project.ExtractionKeyFactory;
import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader;
import com.fuquan.audit.contract.application.project.parse.ParsedProjectDocument;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxContainerGuard;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxTextParser;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import com.fuquan.audit.contract.infra.memory.project.InMemoryProjectRepositories;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import static com.fuquan.audit.contract.application.project.parse.ProjectDocumentParserTest.file;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ProjectFactExtractorTest {
    private static final List<String> V1_FACT_TYPES = List.of(
            "PROJECT_NAME", "PROJECT_NO", "CONTRACT_NO", "CONTRACT_NAME", "PARTY_A", "PARTY_B",
            "SUPPLIER_NAME", "CREDIT_CODE", "AMOUNT_NUMERIC", "AMOUNT_UPPERCASE",
            "LINE_ITEM_KEY", "LINE_ITEM_NAME", "LINE_ITEM_QUANTITY", "LINE_ITEM_UNIT_PRICE",
            "LINE_ITEM_DECLARED_AMOUNT", "AWARD_AMOUNT", "AWARD_SUPPLIER",
            "AWARD_SUPPLIER_CREDIT_CODE");

    @Test
    void potentialContractFactsIgnoreGenericFilenameAndBindExactParseWhileTemplatesStayExcluded() {
        UUID parseResultId = UUID.randomUUID();
        ProjectFile source = source(ProjectFileCategory.UNKNOWN, "001.pdf");
        ParsedProjectDocument parsed = paragraphs(
                "Contract No: CURRENT-001",
                "Party A: Current Buyer",
                "Party B: Current Supplier",
                "Contract Amount: 42.50",
                "合同模板",
                "Contract No: TEMPLATE-999",
                "Party B: Template Supplier",
                "Contract Amount: 999.00");

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extractPotential(source, parsed, parseResultId);

        assertThat(facts).extracting(ExtractionResult::factType)
                .contains("CONTRACT_NO", "PARTY_A", "PARTY_B", "AMOUNT_NUMERIC");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("CONTRACT_NO"))
                .extracting(ExtractionResult::normalizedValue).containsExactly("CURRENT-001");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PARTY_B"))
                .extracting(ExtractionResult::normalizedValue).containsExactly("Current Supplier");
        assertThat(facts).allSatisfy(fact -> assertThat(fact.parseResultId()).isEqualTo(parseResultId));
        assertThat(facts).allSatisfy(fact -> assertThat(fact.extractionKey())
                .isEqualTo(new com.fuquan.audit.contract.application.project.ExtractionKeyFactoryV2().create(
                        source.id(), parseResultId, fact.extractionVersion(), fact.factType(),
                        fact.groupKey(), fact.sourceLocator())));
    }

    @Test
    void extractsNormalizedContractFactsFromRealBodyParagraphShapesWithStableV2Identity() {
        ProjectFile source = source(ProjectFileCategory.CONTRACT_BODY, "采购合同.docx");
        ParsedProjectDocument parsed = paragraphs(
                "软件采购合同",
                "合同类型：采购合同",
                "甲方：上海电力大学",
                "乙方：【 上海具友信息技术有限公司 】",
                "1.本合同标的软件总价款为：人民币【 67000.00】元（大写金额：【 陆万柒仟 】元整）。");
        ProjectFactExtractor extractor = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock());

        List<ExtractionResult> facts = extractor.extract(source, parsed);
        List<ExtractionResult> replay = extractor.extract(source, parsed);

        assertThat(facts).filteredOn(fact -> fact.factType().equals("CONTRACT_TYPE"))
                .singleElement().extracting(ExtractionResult::normalizedValue).isEqualTo("采购合同");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("CONTRACT_NAME"))
                .singleElement().extracting(ExtractionResult::normalizedValue).isEqualTo("软件采购合同");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PARTY_A"))
                .singleElement().extracting(ExtractionResult::normalizedValue).isEqualTo("上海电力大学");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PARTY_B"))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.valueText()).isEqualTo("【 上海具友信息技术有限公司 】");
                    assertThat(fact.normalizedValue()).isEqualTo("上海具友信息技术有限公司");
                    assertThat(fact.sourceText()).isEqualTo("乙方：【 上海具友信息技术有限公司 】");
                });
        assertThat(facts).filteredOn(fact -> fact.factType().equals("AMOUNT_NUMERIC"))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.valueAmount()).isEqualByComparingTo(new BigDecimal("67000"));
                    assertThat(fact.sourceText()).isEqualTo("本合同标的软件总价款为：人民币【 67000.00】元");
                });
        assertThat(facts).filteredOn(fact -> fact.factType().equals("AMOUNT_UPPERCASE"))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.valueText()).isEqualTo("【 陆万柒仟 】元整");
                    assertThat(fact.normalizedValue()).isEqualTo("陆万柒仟元整");
                    assertThat(fact.sourceText()).isEqualTo("大写金额：【 陆万柒仟 】元整");
                });
        assertThat(facts).allSatisfy(fact -> assertThat(fact.extractionVersion())
                .isEqualTo("project-extraction-v2"));
        assertThat(facts).extracting(ExtractionResult::sourceLocator, ExtractionResult::extractionKey)
                .containsExactlyElementsOf(replay.stream()
                        .map(fact -> tuple(fact.sourceLocator(), fact.extractionKey())).toList());
    }

    @Test
    void percentagesThatReferToContractAmountsDoNotBecomeNumericContractFacts() {
        ProjectFile source = source(ProjectFileCategory.CONTRACT_BODY, "contract.docx");
        ParsedProjectDocument parsed = paragraphs(
                "后续维修保养费用原则上不超过合同总价款6%/年",
                "每延期一周，支付合同金额1%的违约金",
                "支付合同金额20%的违约金",
                "合同金额：20%",
                "合同金额：6%/年",
                "合同金额：【20】%",
                "合同金额：【 6.5 】％/年",
                "总金额：1%作为保证金",
                "Contract Amount: 12.5%");

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(source, parsed);

        assertThat(facts).extracting(ExtractionResult::factType).doesNotContain("AMOUNT_NUMERIC");
    }

    @Test
    void validLabeledDecimalAmountAllowsTerminalSentencePeriod() {
        ProjectFile source = source(ProjectFileCategory.CONTRACT_BODY, "contract.docx");
        ParsedProjectDocument parsed = paragraphs("Contract Amount: 42.50.");

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(source, parsed);

        assertThat(facts).filteredOn(fact -> fact.factType().equals("AMOUNT_NUMERIC"))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.valueAmount()).isEqualByComparingTo(new BigDecimal("42.50"));
                    assertThat(fact.sourceText()).isEqualTo("Contract Amount: 42.50");
                });
    }

    @Test
    void labeledTextStopsAtPeerLabelsAndRejectsNonOrganizationValues() {
        ProjectFile source = source(ProjectFileCategory.CONTRACT_BODY, "contract.docx");
        ParsedProjectDocument parsed = paragraphs(
                "甲方：采购单位；乙方：供应单位",
                "项目名称：采购项目甲；采购人：采购单位",
                "项目名称：采购项目乙，",
                "采购人：采购人甲；采购人：采购人乙",
                "采购代理机构：代理机构甲 Procurement Agent: Agent Two",
                "乙方：12345",
                "大写金额：12345");

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(source, parsed);

        assertThat(facts).filteredOn(fact -> fact.factType().equals("PARTY_A"))
                .extracting(ExtractionResult::normalizedValue).containsExactly("采购单位");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PARTY_B"))
                .extracting(ExtractionResult::normalizedValue).containsExactly("供应单位");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PROJECT_NAME"))
                .extracting(ExtractionResult::normalizedValue).containsExactly("采购项目甲", "采购项目乙");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PROJECT_NAME")
                        && fact.normalizedValue().equals("采购项目乙"))
                .singleElement().extracting(ExtractionResult::valueText).isEqualTo("采购项目乙，");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PURCHASER"))
                .extracting(ExtractionResult::normalizedValue)
                .containsExactly("采购单位", "采购人甲", "采购人乙");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PROCUREMENT_AGENT"))
                .extracting(ExtractionResult::normalizedValue).containsExactly("代理机构甲", "Agent Two");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("AMOUNT_UPPERCASE")).isEmpty();
    }

    @Test
    void contractAmountsExcludeBudgetRolesAndNormalizeSupportedTenThousandYuanUnits() {
        ProjectFile source = source(ProjectFileCategory.CONTRACT_BODY, "contract.docx");
        ParsedProjectDocument parsed = paragraphs(
                "预算总金额：250000元",
                "采购预算 总金额：260000元",
                "最高限价总金额：270000元",
                "维修费用总金额：280000元",
                "代理服务费总金额：290000元",
                "合同金额：25万元",
                "合同金额：25.00元",
                "合同金额：25万美元",
                "合同金额：25欧元");

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(source, parsed);

        assertThat(facts).filteredOn(fact -> fact.factType().equals("AMOUNT_NUMERIC"))
                .extracting(ExtractionResult::normalizedValue, ExtractionResult::valueAmount)
                .containsExactly(
                        tuple("250000", new BigDecimal("250000")),
                        tuple("25", new BigDecimal("25.00")));
    }

    @Test
    void bareContractTitleIsLimitedToDocumentFrontAndRejectsContractClauseLanguage() {
        ProjectFile source = source(ProjectFileCategory.CONTRACT_BODY, "contract.docx");
        ParsedProjectDocument parsed = new ParsedProjectDocument(List.of(
                segment(1, "软件采购合同"),
                segment(2, "双方可以解除本合同"),
                segment(3, "双方应当履行合同"),
                segment(30, "设备采购合同")));

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(source, parsed);

        assertThat(facts).filteredOn(fact -> fact.factType().equals("CONTRACT_NAME"))
                .extracting(ExtractionResult::normalizedValue).containsExactly("软件采购合同");
    }

    @Test
    void extractsPurchaserAgentDistinctProjectNumbersAndPackageScopedYuanAmounts() {
        ProjectFile source = source(ProjectFileCategory.TENDER_DOCUMENT, "procurement.docx");
        ParsedProjectDocument parsed = paragraphs(
                "项目编号：SCIT-GZ-ZF-2026-0017",
                "采购人：贵州大学",
                "采购代理机构：四川国际招标有限责任公司",
                "项目名称：贵州大学中外合作办学实验室及平台硬件建设项目，项目编号（或招标编号、政府采购计划编号、采购计划备案文号等，如有）：SCIT-GZ-ZF-2026-0007",
                "贵州大学中外合作办学实验室及平台硬件建设项目预算金额（A包-进口设备）：25万元；",
                "贵州大学中外合作办学实验室及平台硬件建设项目预算金额（B包-国产设备）：415万元；",
                "贵州大学中外合作办学实验室及平台硬件建设项目最高限价（A包-进口设备）：25万元；",
                "贵州大学中外合作办学实验室及平台硬件建设项目最高限价（B包-国产设备）：404.5万元；");

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(source, parsed);

        assertThat(facts).filteredOn(fact -> fact.factType().equals("PURCHASER"))
                .extracting(ExtractionResult::normalizedValue).containsExactly("贵州大学");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PROCUREMENT_AGENT"))
                .extracting(ExtractionResult::normalizedValue).containsExactly("四川国际招标有限责任公司");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PROJECT_NAME"))
                .extracting(ExtractionResult::normalizedValue)
                .containsExactly("贵州大学中外合作办学实验室及平台硬件建设项目");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PARTY_B"))
                .extracting(ExtractionResult::normalizedValue).doesNotContain("四川国际招标有限责任公司");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PROJECT_NO"))
                .extracting(ExtractionResult::normalizedValue, ExtractionResult::sourceText)
                .containsExactly(
                        tuple("SCIT-GZ-ZF-2026-0017", "项目编号：SCIT-GZ-ZF-2026-0017"),
                        tuple("SCIT-GZ-ZF-2026-0007", "项目编号（或招标编号、政府采购计划编号、采购计划备案文号等，如有）：SCIT-GZ-ZF-2026-0007"));
        assertThat(facts).filteredOn(fact -> fact.factType().equals("BUDGET_AMOUNT"))
                .extracting(ExtractionResult::groupKey, ExtractionResult::valueAmount)
                .containsExactly(tuple("PACKAGE:A", new BigDecimal("250000")),
                        tuple("PACKAGE:B", new BigDecimal("4150000")));
        assertThat(facts).filteredOn(fact -> fact.factType().equals("CONTROL_PRICE"))
                .extracting(ExtractionResult::groupKey, ExtractionResult::valueAmount)
                .containsExactly(tuple("PACKAGE:A", new BigDecimal("250000")),
                        tuple("PACKAGE:B", new BigDecimal("4045000")));
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PACKAGE_SCOPE"))
                .extracting(ExtractionResult::groupKey, ExtractionResult::normalizedValue)
                .containsExactlyInAnyOrder(
                        tuple("PACKAGE:A", "进口设备"), tuple("PACKAGE:B", "国产设备"),
                        tuple("PACKAGE:A", "进口设备"), tuple("PACKAGE:B", "国产设备"));
        assertThat(facts).filteredOn(fact -> fact.factType().equals("AMOUNT_NUMERIC")).isEmpty();
    }

    @Test
    void packageScopeKeepsEveryOccurrenceWithUniqueStableIdentityAndCanonicalAmounts() {
        ProjectFile source = source(ProjectFileCategory.TENDER_DOCUMENT, "procurement.docx");
        ParsedProjectDocument parsed = paragraphs(
                "预算金额（A包-设备）：25万元；最高限价（A包-服务）：24万元",
                "预算金额（A包-设备）：25.0万元",
                "预算金额（A包-设备）：250,000元");
        ProjectFactExtractor extractor = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock());

        List<ExtractionResult> facts = extractor.extract(source, parsed);
        List<ExtractionResult> scopes = facts.stream()
                .filter(fact -> fact.factType().equals("PACKAGE_SCOPE")).toList();
        InMemoryProjectRepositories repositories = new InMemoryProjectRepositories();
        scopes.forEach(repositories.extractionResultRepository()::insertIfAbsent);

        assertThat(scopes).hasSize(4);
        assertThat(scopes).extracting(ExtractionResult::normalizedValue)
                .containsExactly("设备", "服务", "设备", "设备");
        assertThat(scopes).extracting(ExtractionResult::extractionKey).doesNotHaveDuplicates();
        assertThat(repositories.extractionResultRepository().findBySourceFileId(source.projectId(), source.id()))
                .hasSize(4);
        assertThat(facts).filteredOn(fact -> fact.factType().equals("BUDGET_AMOUNT"))
                .extracting(ExtractionResult::normalizedValue, ExtractionResult::valueAmount)
                .containsExactly(
                        tuple("250000", new BigDecimal("250000")),
                        tuple("250000", new BigDecimal("250000")),
                        tuple("250000", new BigDecimal("250000")));
    }

    @Test
    void rejectsPlaceholderAndDecorativeValuesAfterNormalization() {
        ProjectFile source = source(ProjectFileCategory.CONTRACT_BODY, "contract.docx");
        ParsedProjectDocument parsed = paragraphs(
                "项目编号：XXXX", "项目编号：【 xxx 】", "乙方：", "乙方：【   】",
                "乙方：--------", "乙方：___", "乙方：...", "乙方：***",
                "乙方：TBD", "乙方：【 N/A 】", "乙方：N-A", "乙方：待填",
                "乙方： 待定 ", "乙方：【暂无】", "乙方：示例");

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(source, parsed);

        assertThat(facts).filteredOn(fact -> fact.factType().equals("PROJECT_NO")
                || fact.factType().equals("PARTY_B")).isEmpty();
    }

    @Test
    void referenceAndFormTemplatesAreScopedButTocAndLaterFormalChapterDoNotPoisonFacts() {
        ProjectFile source = source(ProjectFileCategory.CONTRACT_BODY, "contract.docx");
        ParsedProjectDocument parsed = paragraphs(
                "第六章 政府采购合同（仅供参考）",
                "甲方：参考甲方", "乙方：参考乙方", "统一社会信用代码：91310000123456789X", "合同金额：88.00",
                "第七章 投标文件格式范本", "乙方：表单乙方",
                "第八章 正式合同", "乙方：正式乙方",
                "第六章 政府采购合同（仅供参考） 118", "甲方：目录后甲方");

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(source, parsed);

        assertThat(facts).filteredOn(fact -> fact.factType().equals("PARTY_B"))
                .extracting(ExtractionResult::normalizedValue).containsExactly("正式乙方");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PARTY_A"))
                .extracting(ExtractionResult::normalizedValue).containsExactly("目录后甲方");
        assertThat(facts).extracting(ExtractionResult::normalizedValue)
                .doesNotContain("参考甲方", "参考乙方", "表单乙方", "91310000123456789X", "88.00");
    }

    @Test
    void anyRealTemplateSectionMakesDetachedTablesFailClosed() {
        ProjectFile source = source(ProjectFileCategory.UNKNOWN, "001.docx");
        ParsedProjectDocument parsed = new ParsedProjectDocument(List.of(
                new ParsedProjectDocument.Segment(
                        "docx/body/paragraph:1", "第六章 合同模板", null, 1),
                new ParsedProjectDocument.Segment(
                        "docx/body/paragraph:2", "第七章 正式合同", null, 2)),
                List.of(new ParsedProjectDocument.Table("docx/table:1", List.of(
                        new ParsedProjectDocument.Row("docx/table:1/row:1", List.of(
                                new ParsedProjectDocument.Cell("docx/table:1/row:1/cell:1", "标识"),
                                new ParsedProjectDocument.Cell("docx/table:1/row:1/cell:2", "名称"),
                                new ParsedProjectDocument.Cell("docx/table:1/row:1/cell:3", "数量"),
                                new ParsedProjectDocument.Cell("docx/table:1/row:1/cell:4", "单价"),
                                new ParsedProjectDocument.Cell("docx/table:1/row:1/cell:5", "申报金额"))),
                        new ParsedProjectDocument.Row("docx/table:1/row:2", List.of(
                                new ParsedProjectDocument.Cell("docx/table:1/row:2/cell:1", "TPL-1"),
                                new ParsedProjectDocument.Cell("docx/table:1/row:2/cell:2", "模板设备"),
                                new ParsedProjectDocument.Cell("docx/table:1/row:2/cell:3", "1"),
                                new ParsedProjectDocument.Cell("docx/table:1/row:2/cell:4", "10.00"),
                                new ParsedProjectDocument.Cell("docx/table:1/row:2/cell:5", "10.00")))))));

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extractPotential(source, parsed, UUID.randomUUID());

        assertThat(facts).filteredOn(fact -> fact.factType().startsWith("LINE_ITEM_")).isEmpty();
    }

    @Test
    void standaloneReferenceAndTemplateTitlesAreBoundedAndTocLinesRemainNeutral() {
        ProjectFile source = source(ProjectFileCategory.CONTRACT_BODY, "contract.docx");
        ParsedProjectDocument parsed = paragraphs(
                "参考合同", "甲方：参考甲方", "合同金额：88元",
                "正式合同", "甲方：正式甲方",
                "合同模板", "乙方：模板乙方",
                "第六章 示范文本", "乙方：示范乙方",
                "第七章 正式合同", "乙方：正式乙方",
                "参考合同 118", "甲方：目录后甲方");

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(source, parsed);

        assertThat(facts).filteredOn(fact -> fact.factType().equals("PARTY_A"))
                .extracting(ExtractionResult::normalizedValue).containsExactly("正式甲方", "目录后甲方");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PARTY_B"))
                .extracting(ExtractionResult::normalizedValue).containsExactly("正式乙方");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("AMOUNT_NUMERIC")).isEmpty();
    }

    @Test
    void evidenceExcerptPreservesOriginalWhitespaceWhileNormalizedValueRemainsClean() {
        ProjectFile source = source(ProjectFileCategory.CONTRACT_BODY, "contract.docx");
        ParsedProjectDocument parsed = paragraphs("乙方：【 上海  具友\t信息技术有限公司 】");

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(source, parsed);

        assertThat(facts).filteredOn(fact -> fact.factType().equals("PARTY_B"))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.normalizedValue()).isEqualTo("上海 具友 信息技术有限公司");
                    assertThat(fact.sourceText()).isEqualTo("乙方：【 上海  具友\t信息技术有限公司 】");
                });
    }

    @Test
    void uppercaseAmountAcceptsOnlyAmountLexiconRatherThanArbitraryText() {
        ProjectFile source = source(ProjectFileCategory.CONTRACT_BODY, "contract.docx");
        ParsedProjectDocument parsed = paragraphs(
                "大写金额：待填写金额元整",
                "Amount In Words: UNKNOWN",
                "大写金额：壹佰贰拾元整",
                "Amount In Words: ONE HUNDRED TWENTY YUAN ONLY");

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(source, parsed);

        assertThat(facts).filteredOn(fact -> fact.factType().equals("AMOUNT_UPPERCASE"))
                .extracting(ExtractionResult::normalizedValue)
                .containsExactly("壹佰贰拾元整", "ONEHUNDREDTWENTYYUANONLY");
    }

    @Test
    void projectMaterialCandidatesRespectTemplateSectionsForTenderAndUnknownDocuments() {
        for (ProjectFileCategory category : List.of(
                ProjectFileCategory.TENDER_DOCUMENT, ProjectFileCategory.UNKNOWN)) {
            ProjectFile source = source(category, "procurement.docx");
            ParsedProjectDocument parsed = paragraphs(
                    "项目名称：当前采购项目",
                    "项目编号：SCIT-CURRENT-2026-0007",
                    "采购人：当前采购人",
                    "采购代理机构：当前采购代理",
                    "预算金额（A包 当前设备）：25万元",
                    "第六章 政府采购合同（仅供参考）",
                    "项目名称： 项目编号：SCIT-REFERENCE-2026-0008",
                    "项目名称： 的采购活动",
                    "采购人：参考采购人",
                    "采购代理机构：参考采购代理",
                    "最高限价（B包 参考设备）：30万元",
                    "第七章 投标文件格式范本",
                    "项目名称：格式项目",
                    "项目编号：SCIT-FORM-2026-0009",
                    "采购人：格式采购人",
                    "预算金额（B包 格式设备）：31万元",
                    "第八章 评标办法",
                    "项目名称：恢复采购项目",
                    "项目名称： 项目编号：SCIT-RESUMED-2026-0010",
                    "采购人：恢复采购人",
                    "采购代理机构：恢复采购代理",
                    "最高限价（C包 恢复设备）：35万元");

            List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                    .extract(source, parsed);

            assertThat(facts).as(category.name()).filteredOn(fact -> fact.factType().equals("PROJECT_NAME"))
                    .extracting(ExtractionResult::normalizedValue)
                    .containsExactly("当前采购项目", "恢复采购项目")
                    .doesNotContain("项目编号:", "的采购活动");
            assertThat(facts).as(category.name()).filteredOn(fact -> fact.factType().equals("PROJECT_NO"))
                    .extracting(ExtractionResult::normalizedValue)
                    .containsExactly("SCIT-CURRENT-2026-0007", "SCIT-RESUMED-2026-0010");
            assertThat(facts).as(category.name()).filteredOn(fact -> fact.factType().equals("PURCHASER"))
                    .extracting(ExtractionResult::normalizedValue)
                    .containsExactly("当前采购人", "恢复采购人");
            assertThat(facts).as(category.name()).filteredOn(fact -> fact.factType().equals("PROCUREMENT_AGENT"))
                    .extracting(ExtractionResult::normalizedValue)
                    .containsExactly("当前采购代理", "恢复采购代理");
            assertThat(facts).as(category.name()).filteredOn(fact -> fact.factType().equals("BUDGET_AMOUNT"))
                    .extracting(ExtractionResult::groupKey).containsExactly("PACKAGE:A");
            assertThat(facts).as(category.name()).filteredOn(fact -> fact.factType().equals("CONTROL_PRICE"))
                    .extracting(ExtractionResult::groupKey).containsExactly("PACKAGE:C");
            assertThat(facts).as(category.name()).filteredOn(fact -> fact.factType().equals("PACKAGE_SCOPE"))
                    .extracting(ExtractionResult::groupKey).containsExactly("PACKAGE:A", "PACKAGE:C");
        }
    }

    @Test
    void tableProjectMaterialCandidatesRemainLocalAfterReferenceBodySection() {
        ProjectFile source = source(ProjectFileCategory.UNKNOWN, "procurement.docx");
        ParsedProjectDocument parsed = new ParsedProjectDocument(List.of(
                new ParsedProjectDocument.Segment(
                        "docx/body/paragraph:1", "项目名称：有效采购项目", null, 1),
                new ParsedProjectDocument.Segment(
                        "docx/body/paragraph:2", "第六章 政府采购合同（仅供参考）", null, 2),
                new ParsedProjectDocument.Segment(
                        "docx/body/paragraph:3", "项目名称：参考项目", null, 3),
                new ParsedProjectDocument.Segment(
                        "docx/table:1/row:1/cell:1", "项目名称：有效采购项目", null, null),
                new ParsedProjectDocument.Segment(
                        "docx/table:1/row:2/cell:1", "项目编号：SCIT-TABLE-2026-0011", null, null),
                new ParsedProjectDocument.Segment(
                        "docx/table:1/row:3/cell:1", "采购人：表格采购人", null, null),
                new ParsedProjectDocument.Segment(
                        "docx/table:1/row:4/cell:1", "预算金额（B包 表格设备）：20万元", null, null)));

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(source, parsed);

        assertThat(facts).filteredOn(fact -> fact.factType().equals("PROJECT_NAME"))
                .extracting(ExtractionResult::normalizedValue)
                .containsExactly("有效采购项目", "有效采购项目")
                .doesNotContain("参考项目");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PROJECT_NO"))
                .extracting(ExtractionResult::normalizedValue).containsExactly("SCIT-TABLE-2026-0011");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("PURCHASER"))
                .extracting(ExtractionResult::normalizedValue).containsExactly("表格采购人");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("BUDGET_AMOUNT"))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.groupKey()).isEqualTo("PACKAGE:B");
                    assertThat(fact.valueAmount()).isEqualByComparingTo(new BigDecimal("200000"));
                });
    }

    @Test
    void tableSegmentsFailClosedButUnknownDocumentsExposePotentialFormalScalars() {
        ProjectFile contract = source(ProjectFileCategory.CONTRACT_BODY, "contract.docx");
        ParsedProjectDocument tableCells = new ParsedProjectDocument(List.of(
                new ParsedProjectDocument.Segment("docx/table:1/row:1/cell:1", "乙方：表格乙方"),
                new ParsedProjectDocument.Segment("docx/table:1/row:1/cell:2", "合同金额：99.00")));
        ProjectFile unknown = source(ProjectFileCategory.UNKNOWN, "notes.docx");
        ParsedProjectDocument labeled = paragraphs("乙方：非合同乙方", "合同金额：66.00", "采购人：贵州大学");
        ProjectFactExtractor extractor = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock());

        List<ExtractionResult> tableFacts = extractor.extract(contract, tableCells);
        List<ExtractionResult> unknownFacts = extractor.extract(unknown, labeled);

        assertThat(tableFacts).extracting(ExtractionResult::factType)
                .doesNotContain("PARTY_B", "AMOUNT_NUMERIC");
        assertThat(unknownFacts).extracting(ExtractionResult::factType)
                .contains("PURCHASER", "PARTY_B", "AMOUNT_NUMERIC");
    }

    @Test
    void extractsCompleteV1FactsWithBoundedEvidenceAndStructuredLocations() {
        ProjectFile source = file("contract.pdf", "application/pdf");
        String text = """
                Project Name: Alpha Project
                Project No: P-2026
                Contract No: HT-2026-01
                Contract Name: Equipment Supply
                Contract Type: GOODS
                Party A: Buyer Corp
                Party B: Seller Corp
                Supplier: Seller Corp
                Credit Code: 91310000123456789X
                Contract Amount: 12345.67
                Amount Uppercase: TWELVE THOUSAND
                Line Item [ITEM-1] Name: Server Quantity: 2 Unit Price: 6000.00 Declared Amount: 12000.00
                Winning Amount: 12300.00
                Winning Supplier: Seller Corp
                Winning Supplier Credit Code: 91310000123456789X
                """;
        ParsedProjectDocument parsed = new ParsedProjectDocument(List.of(
                new ParsedProjectDocument.Segment("pdf/page:2", text, 2, null)));

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(source, parsed);

        assertThat(facts).extracting(ExtractionResult::factType).containsAll(V1_FACT_TYPES);
        assertThat(facts).filteredOn(fact -> fact.factType().equals("SUPPLIER_NAME"))
                .singleElement().extracting(ExtractionResult::normalizedValue).isEqualTo("Seller Corp");
        assertThat(facts).extracting(ExtractionResult::factType)
                .doesNotContain("SUPPLIER_CREDIT_CODE", "CONTRACT_AMOUNT");
        assertThat(facts).allSatisfy(fact -> {
            assertThat(fact.sourceText()).isNotBlank().hasSizeLessThanOrEqualTo(240);
            assertThat(fact.pageNumber()).isEqualTo(2);
            assertThat(fact.paragraphIndex()).isNull();
            assertThat(fact.sourceLocator()).contains("occurrence:");
        });
        assertThat(facts).filteredOn(fact -> fact.factType().startsWith("LINE_ITEM_"))
                .extracting(ExtractionResult::groupKey).containsOnly("ITEM-1");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("AMOUNT_NUMERIC"))
                .singleElement().extracting(ExtractionResult::valueAmount).isEqualTo(new java.math.BigDecimal("12345.67"));
    }

    @Test
    void docxParagraphCarriesParagraphIndexAndReplayKeepsExtractionKeys() {
        ProjectFile source = file("contract.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        ParsedProjectDocument parsed = new ParsedProjectDocument(List.of(
                new ParsedProjectDocument.Segment("docx/body/paragraph:7", "合同编号：HT-7", null, 7)));
        ProjectFactExtractor extractor = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock());

        List<ExtractionResult> first = extractor.extract(source, parsed);
        List<ExtractionResult> replay = extractor.extract(source, parsed);

        assertThat(first).extracting(ExtractionResult::paragraphIndex).containsOnly(7);
        assertThat(first).extracting(ExtractionResult::extractionKey)
                .containsExactlyElementsOf(replay.stream().map(ExtractionResult::extractionKey).toList());
    }

    @Test
    void winningCreditCodeIsNotAlsoPublishedAsTheOrdinarySupplierCreditCode() {
        ProjectFile source = file("award.pdf", "application/pdf");
        ParsedProjectDocument parsed = new ParsedProjectDocument(List.of(
                new ParsedProjectDocument.Segment("pdf/page:1",
                        "Winning Credit Code: 91310000123456789X", 1, null)));

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(source, parsed);

        assertThat(facts).extracting(ExtractionResult::factType)
                .containsExactly("AWARD_SUPPLIER_CREDIT_CODE");
    }

    @Test
    void realParserOutputProducesExactlyThreeCompleteLineItemsWithoutLegacyDuplicates() throws Exception {
        ProjectFile source = file("contract.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        ProjectIntakeProperties intake = ProjectIntakeProperties.defaults();
        ProjectDocxTextParser parser = new ProjectDocxTextParser(
                new ProjectDocxContainerGuard(new SelectedProjectArchiveReader(intake), intake));
        ParsedProjectDocument parsed = parser.parse(source, new ByteArrayInputStream(lineItemDocx()));

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(source, parsed);

        assertThat(facts).hasSize(15);
        assertThat(facts).extracting(ExtractionResult::groupKey)
                .containsExactly(
                        "SOFT-01", "SOFT-01", "SOFT-01", "SOFT-01", "SOFT-01",
                        "SERV-01", "SERV-01", "SERV-01", "SERV-01", "SERV-01",
                        "TRAIN-01", "TRAIN-01", "TRAIN-01", "TRAIN-01", "TRAIN-01");
        assertLine(facts, "SOFT-01", "智慧维修系统软件许可", "1", "40000.00", "40000.00");
        assertLine(facts, "SERV-01", "部署实施与数据初始化", "1", "17000.00", "17000.00");
        assertLine(facts, "TRAIN-01", "培训与接口对接服务", "1", "10000.00", "10000.00");
        assertThat(facts).filteredOn(fact -> fact.factType().equals("LINE_ITEM_NAME"))
                .extracting(ExtractionResult::sourceLocator)
                .containsExactly(
                        "docx/table:1/row:2/cell:2/fact:LINE_ITEM_NAME/occurrence:1",
                        "docx/table:1/row:3/cell:2/fact:LINE_ITEM_NAME/occurrence:1",
                        "docx/table:1/row:4/cell:2/fact:LINE_ITEM_NAME/occurrence:1");
    }

    @Test
    void typedTableFactsUseUniqueBoundedRowContextWhilePreservingExactCellLocatorsAndValues() throws Exception {
        ProjectFile source = file("contract.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        ProjectIntakeProperties intake = ProjectIntakeProperties.defaults();
        ProjectDocxTextParser parser = new ProjectDocxTextParser(
                new ProjectDocxContainerGuard(new SelectedProjectArchiveReader(intake), intake));
        ParsedProjectDocument parsed = parser.parse(source, new ByteArrayInputStream(lineItemDocx()));

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extract(source, parsed);

        assertLine(facts, "SOFT-01", "智慧维修系统软件许可", "1", "40000.00", "40000.00");
        assertLine(facts, "SERV-01", "部署实施与数据初始化", "1", "17000.00", "17000.00");
        assertLine(facts, "TRAIN-01", "培训与接口对接服务", "1", "10000.00", "10000.00");
        assertThat(facts).allSatisfy(fact -> {
            assertThat(fact.sourceText()).hasSizeLessThanOrEqualTo(240).doesNotContain("|", ";");
            assertThat(normalizedOccurrences(parsed.fullText(), fact.sourceText())).isEqualTo(1);
        });
        assertThat(facts).filteredOn(fact -> fact.groupKey().equals("SOFT-01"))
                .extracting(ExtractionResult::sourceText).containsOnly(
                        "SOFT-01 智慧维修系统软件许可 1 40000.00 40000.00");
        assertThat(facts).filteredOn(fact -> fact.groupKey().equals("SOFT-01")
                        && fact.factType().equals("LINE_ITEM_UNIT_PRICE"))
                .singleElement().extracting(ExtractionResult::sourceLocator)
                .isEqualTo("docx/table:1/row:2/cell:4/fact:LINE_ITEM_UNIT_PRICE/occurrence:1");
        assertThat(facts).filteredOn(fact -> fact.groupKey().equals("SOFT-01")
                        && fact.factType().equals("LINE_ITEM_DECLARED_AMOUNT"))
                .singleElement().extracting(ExtractionResult::sourceLocator)
                .isEqualTo("docx/table:1/row:2/cell:5/fact:LINE_ITEM_DECLARED_AMOUNT/occurrence:1");
    }

    @Test
    void finalTransactionSynonymsRemainDistinctFromCandidateQuotesAndBudget() {
        ProjectFile source = source(ProjectFileCategory.AWARD_NOTICE, "notice.docx");
        ParsedProjectDocument parsed = paragraphs(
                "第一成交候选人报价：6.5万元",
                "第一成交候选人成交金额：6.5万元",
                "未成交金额：0元",
                "采购预算金额：8万元",
                "成交金额：6.7万元",
                "成交供应商：示例科技有限公司");

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extractPotential(source, parsed, UUID.randomUUID());

        assertThat(facts).filteredOn(fact -> fact.factType().equals("AWARD_AMOUNT"))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.valueAmount()).isEqualByComparingTo("67000");
                    assertThat(fact.sourceLocator()).contains("paragraph:5");
                });
        assertThat(facts).filteredOn(fact -> fact.factType().equals("AWARD_SUPPLIER"))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.normalizedValue()).isEqualTo("示例科技有限公司");
                    assertThat(fact.sourceLocator()).contains("paragraph:6");
                });
        assertThat(facts).filteredOn(fact -> fact.factType().equals("SUPPLIER_NAME")).isEmpty();
    }

    @Test
    void keyValueAwardTableKeepsTheValueCellAsEvidenceSource() {
        ProjectFile source = source(ProjectFileCategory.AWARD_NOTICE, "notice.docx");
        ParsedProjectDocument.Row row = new ParsedProjectDocument.Row("docx/table:1/row:1", List.of(
                new ParsedProjectDocument.Cell("docx/table:1/row:1/cell:1", "成交供应商"),
                new ParsedProjectDocument.Cell("docx/table:1/row:1/cell:2", "示例科技有限公司"),
                new ParsedProjectDocument.Cell("docx/table:1/row:1/cell:3", "成交金额"),
                new ParsedProjectDocument.Cell("docx/table:1/row:1/cell:4", "67,000.00元")));
        ParsedProjectDocument parsed = new ParsedProjectDocument(List.of(), List.of(
                new ParsedProjectDocument.Table("docx/table:1", List.of(row))));

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extractPotential(source, parsed, UUID.randomUUID());

        assertThat(facts).filteredOn(fact -> fact.factType().equals("AWARD_AMOUNT"))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.valueAmount()).isEqualByComparingTo("67000");
                    assertThat(fact.sourceLocator()).startsWith("docx/table:1/row:1/cell:4/");
                    assertThat(fact.sourceText()).contains("成交金额", "67,000.00元");
                });
        assertThat(facts).filteredOn(fact -> fact.factType().equals("AWARD_SUPPLIER"))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.normalizedValue()).isEqualTo("示例科技有限公司");
                    assertThat(fact.sourceLocator()).startsWith("docx/table:1/row:1/cell:2/");
                });
    }

    @Test
    void candidateHeadingOnTheSamePdfPageDoesNotBecomeFinalAward() {
        ProjectFile source = source(ProjectFileCategory.AWARD_NOTICE, "notice.pdf");
        ParsedProjectDocument parsed = new ParsedProjectDocument(List.of(
                new ParsedProjectDocument.Segment("pdf/page:1",
                        "第一成交候选人：甲公司\n成交金额：6.5万元\n成交供应商：甲公司", 1, null)));

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extractPotential(source, parsed, UUID.randomUUID());

        assertThat(facts).filteredOn(fact -> fact.factType().equals("AWARD_AMOUNT")
                || fact.factType().equals("AWARD_SUPPLIER")).isEmpty();
    }

    @Test
    void candidateHeadingInAwardKeyValueTableDoesNotBecomeFinalAward() {
        ProjectFile source = source(ProjectFileCategory.AWARD_NOTICE, "notice.docx");
        ParsedProjectDocument.Table table = new ParsedProjectDocument.Table("docx/table:1", List.of(
                new ParsedProjectDocument.Row("docx/table:1/row:1", List.of(
                        new ParsedProjectDocument.Cell("docx/table:1/row:1/cell:1", "第一成交候选人"),
                        new ParsedProjectDocument.Cell("docx/table:1/row:1/cell:2", "甲公司"))),
                new ParsedProjectDocument.Row("docx/table:1/row:2", List.of(
                        new ParsedProjectDocument.Cell("docx/table:1/row:2/cell:1", "成交金额"),
                        new ParsedProjectDocument.Cell("docx/table:1/row:2/cell:2", "6.5万元"))),
                new ParsedProjectDocument.Row("docx/table:1/row:3", List.of(
                        new ParsedProjectDocument.Cell("docx/table:1/row:3/cell:1", "成交供应商"),
                        new ParsedProjectDocument.Cell("docx/table:1/row:3/cell:2", "甲公司")))));

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extractPotential(source, new ParsedProjectDocument(List.of(), List.of(table)), UUID.randomUUID());

        assertThat(facts).filteredOn(fact -> fact.factType().equals("AWARD_AMOUNT")
                || fact.factType().equals("AWARD_SUPPLIER")).isEmpty();
    }

    @Test
    void candidateTableInlineCellCannotBypassTheTableContextGuard() {
        ProjectFile source = source(ProjectFileCategory.AWARD_NOTICE, "notice.docx");
        ParsedProjectDocument.Cell valueCell = new ParsedProjectDocument.Cell(
                "docx/table:1/row:2/cell:1", "成交金额：6.5万元");
        ParsedProjectDocument parsed = new ParsedProjectDocument(List.of(
                new ParsedProjectDocument.Segment(valueCell.sourceLocator(), valueCell.text(), null, null)),
                List.of(new ParsedProjectDocument.Table("docx/table:1", List.of(
                        new ParsedProjectDocument.Row("docx/table:1/row:1", List.of(
                                new ParsedProjectDocument.Cell("docx/table:1/row:1/cell:1", "第一成交候选人"))),
                        new ParsedProjectDocument.Row("docx/table:1/row:2", List.of(valueCell))))));

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extractPotential(source, parsed, UUID.randomUUID());

        assertThat(facts).filteredOn(fact -> fact.factType().equals("AWARD_AMOUNT")).isEmpty();
    }

    @Test
    void finalAwardTableInlineCellsStillKeepTheirOwnLocators() {
        ProjectFile source = source(ProjectFileCategory.AWARD_NOTICE, "notice.docx");
        ParsedProjectDocument.Cell amount = new ParsedProjectDocument.Cell(
                "docx/table:1/row:2/cell:1", "成交金额：6.7万元");
        ParsedProjectDocument.Cell supplier = new ParsedProjectDocument.Cell(
                "docx/table:1/row:3/cell:1", "成交供应商：乙公司");
        ParsedProjectDocument parsed = new ParsedProjectDocument(List.of(
                new ParsedProjectDocument.Segment(amount.sourceLocator(), amount.text(), null, null),
                new ParsedProjectDocument.Segment(supplier.sourceLocator(), supplier.text(), null, null)),
                List.of(new ParsedProjectDocument.Table("docx/table:1", List.of(
                        new ParsedProjectDocument.Row("docx/table:1/row:1", List.of(
                                new ParsedProjectDocument.Cell("docx/table:1/row:1/cell:1", "成交结果"))),
                        new ParsedProjectDocument.Row("docx/table:1/row:2", List.of(amount)),
                        new ParsedProjectDocument.Row("docx/table:1/row:3", List.of(supplier))))));

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extractPotential(source, parsed, UUID.randomUUID());

        assertThat(facts).filteredOn(fact -> fact.factType().equals("AWARD_AMOUNT"))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.valueAmount()).isEqualByComparingTo("67000");
                    assertThat(fact.sourceLocator()).startsWith(amount.sourceLocator());
                });
        assertThat(facts).filteredOn(fact -> fact.factType().equals("AWARD_SUPPLIER"))
                .singleElement().extracting(ExtractionResult::sourceLocator)
                .asString().startsWith(supplier.sourceLocator());
    }

    @Test
    void explicitFinalResultAfterCandidateSectionCanStillBeExtracted() {
        ProjectFile source = source(ProjectFileCategory.AWARD_NOTICE, "notice.pdf");
        ParsedProjectDocument parsed = new ParsedProjectDocument(List.of(
                new ParsedProjectDocument.Segment("pdf/page:1",
                        "第一成交候选人：甲公司\n候选报价：6.5万元\n最终成交结果\n成交金额：6.7万元\n成交供应商：乙公司", 1, null)));

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extractPotential(source, parsed, UUID.randomUUID());

        assertThat(facts).filteredOn(fact -> fact.factType().equals("AWARD_AMOUNT"))
                .singleElement().extracting(ExtractionResult::valueAmount).isEqualTo(new BigDecimal("67000"));
        assertThat(facts).filteredOn(fact -> fact.factType().equals("AWARD_SUPPLIER"))
                .singleElement().extracting(ExtractionResult::normalizedValue).isEqualTo("乙公司");
    }

    @Test
    void noColonAwardNoticeLinesKeepFinalAmountAndSupplierDistinctFromCandidateQuote() {
        ProjectFile source = source(ProjectFileCategory.AWARD_NOTICE, "notice.pdf");
        ParsedProjectDocument parsed = new ParsedProjectDocument(List.of(
                new ParsedProjectDocument.Segment("pdf/page:1",
                        "成交结果\n成交供应商 示例科技有限公司\n成交金额 人民币67,000.00元（大写金额）", 1, null)));

        List<ExtractionResult> facts = new ProjectFactExtractor(new ExtractionKeyFactory(), fixedClock())
                .extractPotential(source, parsed, UUID.randomUUID());

        assertThat(facts).filteredOn(fact -> fact.factType().equals("AWARD_AMOUNT"))
                .singleElement().satisfies(fact -> {
                    assertThat(fact.valueAmount()).isEqualByComparingTo("67000");
                    assertThat(fact.sourceText()).startsWith("成交金额");
                    assertThat(fact.sourceLocator()).startsWith("pdf/page:1/");
                });
        assertThat(facts).filteredOn(fact -> fact.factType().equals("AWARD_SUPPLIER"))
                .singleElement().extracting(ExtractionResult::normalizedValue).isEqualTo("示例科技有限公司");
    }

    private static void assertLine(List<ExtractionResult> facts, String key, String name,
                                   String quantity, String unitPrice, String declaredAmount) {
        assertThat(facts).filteredOn(fact -> fact.groupKey().equals(key))
                .extracting(ExtractionResult::factType, ExtractionResult::valueText,
                        ExtractionResult::valueAmount)
                .containsExactly(
                        tuple("LINE_ITEM_KEY", key, null),
                        tuple("LINE_ITEM_NAME", name, null),
                        tuple("LINE_ITEM_QUANTITY", quantity, new BigDecimal(quantity)),
                        tuple("LINE_ITEM_UNIT_PRICE", unitPrice, new BigDecimal(unitPrice)),
                        tuple("LINE_ITEM_DECLARED_AMOUNT", declaredAmount, new BigDecimal(declaredAmount)));
    }

    private static byte[] lineItemDocx() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFTable table = document.createTable(4, 5);
            String[][] values = {
                    {"标识", "货物/服务名称", "数量", "单价（元）", "申报金额（元）"},
                    {"SOFT-01", "智慧维修系统软件许可", "1", "40000.00", "40000.00"},
                    {"SERV-01", "部署实施与数据初始化", "1", "17000.00", "17000.00"},
                    {"TRAIN-01", "培训与接口对接服务", "1", "10000.00", "10000.00"}
            };
            for (int row = 0; row < values.length; row++) {
                for (int cell = 0; cell < values[row].length; cell++) {
                    table.getRow(row).getCell(cell).setText(values[row][cell]);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        }
    }

    private static Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);
    }

    private static ParsedProjectDocument paragraphs(String... values) {
        List<ParsedProjectDocument.Segment> segments = new java.util.ArrayList<>();
        for (int index = 0; index < values.length; index++) {
            segments.add(new ParsedProjectDocument.Segment("docx/body/paragraph:" + (index + 1),
                    values[index], null, index + 1));
        }
        return new ParsedProjectDocument(segments);
    }

    private static ParsedProjectDocument.Segment segment(int index, String value) {
        return new ParsedProjectDocument.Segment("docx/body/paragraph:" + index, value, null, index);
    }

    private static ProjectFile source(ProjectFileCategory category, String filename) {
        ProjectFile base = file(filename,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        return new ProjectFile(base.id(), base.projectId(), base.parentArchiveFileId(),
                base.normalizedRelativePath(), base.originalFilename(), base.mediaType(), category,
                base.ingestionStatus(), base.parseStatus(), base.storageKey(), base.sha256(), base.sizeBytes(),
                base.parsedText(), base.safeReasonCode(), base.createdAt(), base.updatedAt(), base.parseProvenance());
    }

    private static int normalizedOccurrences(String text, String excerpt) {
        String normalizedText = Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\s+", "");
        String normalizedExcerpt = Normalizer.normalize(excerpt, Normalizer.Form.NFKC).replaceAll("\\s+", "");
        return normalizedText.split(Pattern.quote(normalizedExcerpt), -1).length - 1;
    }
}
