package com.fuquan.audit.budget.application;

import static org.assertj.core.api.Assertions.*;
import static com.fuquan.audit.budget.application.BudgetUploadedFacts.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BudgetUploadedMaterialAnalyzerTest {
    @ParameterizedTest @ValueSource(strings={"办公设备，","办公设备,","办公设备；","办公设备;"})
    void subjectSentenceBoundaryDoesNotCreateFalseMissingOrUnapprovedItems(String subject) {
        var d=file(row("projectCode","QA26-B04","subjectName","办公设备","declaredAmount","18000元"));
        var a=file(row("projectCode","QA26-B04","subjectName",subject,"approvalAmount","20000元"),
                   row("projectCode","QA26-B04","subjectName","家具用具，","approvalAmount","5000元"));
        var result=engine.analyze(List.of(d,a));
        assertThat(result.get(d.taskId()).lines()).singleElement().satisfies(line->{
            assertThat(line.status()).isEqualTo("WITHIN_APPROVAL");
            assertThat(line.approvalAmount()).isEqualByComparingTo("20000");
            assertThat(line.sources()).anySatisfy(e->assertThat(e.value()).isEqualTo(subject));
        });
        assertThat(result.get(a.taskId()).lines()).extracting(Line::status).containsExactly("WITHIN_APPROVAL","MISSING_DECLARATION");
    }
    @Test void punctuationWithinSubjectAndProjectOrSubjectCodesRemainSignificant() {
        var d=file(row("projectCode","P,","subjectName","办公,设备","declaredAmount","18000元"));
        var a=file(row("projectCode","P","subjectName","办公设备","approvalAmount","20000元"));
        assertThat(first(d,a).approvalAmount()).isNull();
        var sameProject=file(row("projectCode","P,","subjectName","办公设备","approvalAmount","20000元"));
        assertThat(first(d,sameProject).approvalAmount()).isNull();
        assertThat(first(file(row("projectCode","P","subjectCode","S,","declaredAmount","18000元")),
             file(row("projectCode","P","subjectCode","S","approvalAmount","20000元"))).approvalAmount()).isNull();
    }
    @Test void chineseYearSuffixCannotHideAnOtherwiseMatchingConflictingApprovalVersion() {
        var d=file(row("projectCode","P","subjectName","设备","budgetYear","2026年","declaredAmount","120000"));
        var a=file(row("projectCode","P","subjectName","设备","budgetYear","2026年度","approvalAmount","100000"));
        var b=file(row("projectCode","P","subjectName","设备","budgetYear","2026","approvalAmount","150000"));
        assertThat(engine.analyze(List.of(d,a,b)).values()).allSatisfy(f->assertThat(f.lines()).allSatisfy(l->{
            assertThat(l.status()).isEqualTo("CONFLICT");assertThat(l.approvalAmount()).isNull();}));
        var other=file(row("projectCode","P","subjectName","设备","budgetYear","2025年","approvalAmount","90000"));
        assertThat(first(d,a,other).status()).isEqualTo("OVER_APPROVAL");
        assertThat(first(d,a,other).approvalAmount()).isEqualByComparingTo("100000");
    }
    @Test void excludedFactsSurviveSerializationAndCannotProduceCleanOrMissingSubjectConclusions() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var snapshot=new Snapshot(List.of(row("projectCode","P","subjectCode","S1","declaredAmount","50","approvalAmount","100")),
            List.of(new ExtractionIssue("subjectName","p1","EVIDENCE_UNVERIFIED")));
        assertThat(json.readValue(json.writeValueAsString(snapshot),Snapshot.class)).isEqualTo(snapshot);
        assertThat(json.readValue("{\"rows\":[]}",Snapshot.class).issues()).isEmpty();
        var incomplete=new Input(UUID.randomUUID(),"仿真.txt","PARSED",snapshot);
        var other=file(row("projectCode","P","subjectCode","S2","approvalAmount","80"));
        var result=engine.analyze(List.of(incomplete,other));
        assertThat(result.get(incomplete.taskId()).coverage()).isEqualTo("PARTIAL");
        assertThat(result.get(other.taskId()).coverage()).isEqualTo("PARTIAL");
        assertThat(result.get(incomplete.taskId()).summary()).contains("未能与原文核实");
        assertThat(result.values()).allSatisfy(f->assertThat(f.lines()).allSatisfy(l->assertThat(l.status()).isEqualTo("INSUFFICIENT")));
    }
    @Test void fullyVerifiedBatchIsDistinguishedFromCompletedButIncompleteAnalysis() {
        var input=file(row("projectCode","P","subjectCode","S","declaredAmount","100","approvalAmount","120"));
        var result=engine.analyze(List.of(input)).get(input.taskId());
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.coverage()).isEqualTo("COMPLETE");
    }
    @Test void finishedDoesNotImplyCompleteWhenAmountsConflictAreMissingOrNoRowsExist() throws Exception {
        for(var input:List.of(file(),file(row("subjectName","设备")),
                file(row("subjectName","设备","declaredAmount","100","approvalAmount","无法确认")),
                file(row("subjectName","设备","declaredAmount","100","approvalAmount","200","approvalAmount","300")))) {
            var result=engine.analyze(List.of(input)).get(input.taskId());
            assertThat(result.status()).isEqualTo("COMPLETED");
            assertThat(result.coverage()).isEqualTo("PARTIAL");
        }
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var historical=json.readValue("{\"status\":\"COMPLETED\",\"summary\":\"历史结果\",\"lines\":[]}",FileResult.class);
        assertThat(historical.coverage()).isNull();
        assertThat(json.readValue(json.writeValueAsString(historical),FileResult.class)).isEqualTo(historical);
    }
    @Test void equalAmountsOnDifferentSourceRowsForOneSupplierCannotSilentlyBecomeOneExpense() {
        var declarations=new ArrayList<Row>();
        for(int i=1;i<=2;i++) {
            String locator="excel/sheet:1/row:"+i;
            declarations.add(new Row(locator,List.of(new Fact("projectCode","P",locator,"P"),
                new Fact("subjectName","设备",locator,"设备"),new Fact("supplierName","供应商甲",locator,"供应商甲"),
                new Fact("declaredAmount","6000",locator,"6000"))));
        }
        var result=first(file(declarations.toArray(Row[]::new)),file(row("projectCode","P","subjectName","设备","approvalAmount","10000")));
        assertThat(result.status()).isEqualTo("INSUFFICIENT");
        assertThat(result.declaredAmount()).isEqualByComparingTo("6000");
        assertThat(result.opinion()).contains("单条识别金额", "未自动相加");
    }
    @Test void modelAndDeterministicColumnEvidenceForOneRowAreNotTwoExpenses() {
        String locator="excel/sheet:1/row:2";
        var evidence=new Row(locator,List.of(new Fact("subjectName","设备",locator,"设备"),
            new Fact("declaredAmount","6000元",locator,"6000元"),
            new Fact("declaredAmount","6000",locator+"/col:3","6000"),
            new Fact("approvalAmount","10000",locator,"10000")));
        assertThat(first(file(evidence)).status()).isEqualTo("WITHIN_APPROVAL");
        assertThat(first(file(evidence)).declaredAmount()).isEqualByComparingTo("6000");
    }
    @Test void equalAmountsOnDistinctCitedSupplierDetailsAreAddedBeforeComparison() {
        var d=file(row("projectCode","P","subjectName","设备","supplierName","供应商甲","declaredAmount","6000元"),
            row("projectCode","P","subjectName","设备","supplierName","供应商乙","declaredAmount","陆仟元整"));
        var a=file(row("projectCode","P","subjectName","设备","approvalAmount","10000元"));
        var result=first(d,a);assertThat(result.declaredAmount()).isEqualByComparingTo("12000");assertThat(result.status()).isEqualTo("OVER_APPROVAL");
        var duplicate=file(row("projectCode","P","subjectName","设备","supplierName","供应商甲","declaredAmount","6000.00元"));
        assertThat(first(d,a,duplicate).declaredAmount()).isEqualByComparingTo("12000");
    }
    @Test void conflictingVersionsForTheSameSupplierAreNotAddedOrSelected() {
        var d=file(row("projectCode","P","subjectName","设备","supplierName","供应商甲","declaredAmount","6000"),
            row("projectCode","P","subjectName","设备","supplierName","供应商甲","declaredAmount","8000"),
            row("projectCode","P","subjectName","设备","supplierName","供应商乙","declaredAmount","6000"));
        assertThat(first(d).status()).isEqualTo("CONFLICT");
    }
    @Test void financialUppercaseComparisonAndMismatchedDualAmountsAreDeterministic() {
        var line=first(file(row("subjectName","设备","declaredAmount","壹拾贰万叁仟肆佰伍拾陆元柒角捌分","approvalAmount","壹拾万元整")));
        assertThat(line.status()).isEqualTo("OVER_APPROVAL");assertThat(line.opinion()).contains("23456.78");
        assertThat(first(file(row("subjectName","设备","declaredAmount","120000元（大写：壹拾万元整）","approvalAmount","150000元"))).status()).isEqualTo("INVALID_AMOUNT");
    }
    @Test void completeComparableMaterialsIdentifyUnapprovedAndMissingSubjectsWithEvidence() {
        var d=file(row("projectCode","P","subjectCode","S1","declaredAmount","120"),row("projectCode","P","subjectCode","S2","declaredAmount","50"));
        var a=file(row("projectCode","P","subjectCode","S1","approvalAmount","100"),row("projectCode","P","subjectCode","S3","approvalAmount","80"));
        var result=engine.analyze(List.of(d,a));
        assertThat(result.get(d.taskId()).lines()).extracting(Line::status).containsExactly("OVER_APPROVAL","UNAPPROVED_SUBJECT");
        assertThat(result.get(a.taskId()).lines()).extracting(Line::status).containsExactly("OVER_APPROVAL","MISSING_DECLARATION");
        assertThat(result.get(a.taskId()).lines().get(1).declaredAmount()).isNull();
        assertThat(result.get(a.taskId()).lines().get(1).sources()).extracting(Evidence::taskId).contains(a.taskId());
    }
    @Test void absentOrFailedCounterpartNeverBecomesAProvenOmission() {
        var d=file(row("projectCode","P","subjectCode","S1","declaredAmount","100"));
        var a=file(row("projectCode","P","subjectCode","S2","approvalAmount","100"));
        assertThat(first(d).status()).isEqualTo("INSUFFICIENT");
        assertThat(first(a).status()).isEqualTo("INSUFFICIENT");
        for(String state:List.of("FAILED","UNKNOWN","PARSED")) {
            var incomplete=new Input(UUID.randomUUID(),"其他材料",state,null);
            assertThat(first(d,a,incomplete).status()).isEqualTo("INSUFFICIENT");
        }
    }
    @Test void differenceClassificationCannotCrossProjectsYearsDepartmentsOrAmbiguousVersions() {
        var d=file(row("projectCode","P","subjectCode","S1","declaredAmount","100","budgetYear","2026","departmentName","甲"));
        var otherProject=file(row("projectCode","Q","subjectCode","S2","approvalAmount","100","budgetYear","2026","departmentName","甲"));
        var otherYear=file(row("projectCode","P","subjectCode","S2","approvalAmount","100","budgetYear","2025","departmentName","甲"));
        var otherDepartment=file(row("projectCode","P","subjectCode","S2","approvalAmount","100","budgetYear","2026","departmentName","乙"));
        for(var other:List.of(otherProject,otherYear,otherDepartment))assertThat(first(d,other).status()).isEqualTo("INSUFFICIENT");
        var anonymous=file(row("subjectCode","S1","declaredAmount","100"));
        assertThat(first(anonymous,otherProject).status()).isEqualTo("INSUFFICIENT");
    }
    private final BudgetUploadedMaterialAnalyzer engine=new BudgetUploadedMaterialAnalyzer();
    @Test void exactProjectCodeAssociatesAliasTitlesButNotDifferentSubjectsOrContexts() {
        var d=file(row("projectCode","P1","projectName","北区网络改造","subjectName","网络设备","budgetYear","2026","departmentName","信息部","declaredAmount","84000"));
        var a=file(row("projectCode","P1","projectName","北区网络升级工程","subjectName","网络设备","budgetYear","2026","departmentName","信息部","approvalAmount","80000"));
        assertThat(first(d,a).status()).isEqualTo("CONFLICT");
        assertThat(first(d,a).approvalAmount()).isNull();
        for(String[] other:List.of(new String[]{"projectCode","P2"},new String[]{"budgetYear","2025"},new String[]{"departmentName","档案部"},new String[]{"subjectName","培训服务"})) {
            var fields=new LinkedHashMap<String,String>(Map.of("projectCode","P1","projectName","北区网络升级工程","subjectName","网络设备","budgetYear","2026","departmentName","信息部","approvalAmount","80000"));
            fields.put(other[0],other[1]);var flat=new ArrayList<String>();fields.forEach((k,v)->{flat.add(k);flat.add(v);});
            assertThat(first(d,file(row(flat.toArray(String[]::new)))).approvalAmount()).isNull();
        }
        var conflicting=file(row("projectCode","P1","projectCode","P2","subjectName","网络设备","approvalAmount","80000"));
        assertThat(first(d,conflicting).approvalAmount()).isNull();
        var renamedSubject=file(row("projectCode","P1","subjectCode","S1","subjectName","培训","approvalAmount","80000"));
        var codedDeclaration=file(row("projectCode","P1","subjectCode","S1","subjectName","设备","declaredAmount","84000"));
        assertThat(first(codedDeclaration,renamedSubject).approvalAmount()).isNull();
    }
    @Test void sameProjectCodeWithDifferentNamesIsNotSilentlyCombined() {
        var first=file(row("projectCode","P1","projectName","北区网络改造","subjectCode","S1","subjectName","设备","budgetYear","2026","declaredAmount","84000"));
        var second=file(row("projectCode","P1","projectName","北区网络升级工程","subjectCode","S1","subjectName","设备","budgetYear","2026","approvalAmount","80000"));
        assertThat(first(first,second).status()).isEqualTo("CONFLICT");
        assertThat(first(first,second).opinion()).contains("项目编号一致").contains("项目名称不一致");
    }
    @Test void identifiedSubjectWithoutEitherAmountExplainsWhyComparisonWasSkipped() {
        var line=first(file(row("projectCode","P1","subjectName","设备")));
        assertThat(line.status()).isEqualTo("INSUFFICIENT");
        assertThat(line.opinion()).contains("没有可核实的申报金额或批复金额");
    }
    static Row row(String... fields) {var facts=new ArrayList<Fact>();for(int i=0;i<fields.length;i+=2)facts.add(new Fact(fields[i],fields[i+1],"original-row",fields[i]+"："+fields[i+1]));return new Row(UUID.randomUUID().toString(),facts);}
    static Input file(Row... rows) {return new Input(UUID.randomUUID(),"合成预算材料", "PARSED",new Snapshot(List.of(rows)));}
    Line first(Input target,Input... rest) {var inputs=new ArrayList<Input>();inputs.add(target);inputs.addAll(List.of(rest));return engine.analyze(inputs).get(target.taskId()).lines().get(0);}
    @Test void sameFileRecognizesBothAmountsWithoutAnyDatabaseBusinessData() {
        var source=file(row("subjectName","办公设备","declaredAmount","31200","approvalAmount","32760"));
        var line=first(source);assertThat(line.status()).isEqualTo("WITHIN_APPROVAL");assertThat(line.approvalAmount()).isEqualByComparingTo("32760");assertThat(line.opinion()).contains("供工作参考");
    }
    @Test void associatesExplicitProjectAndSubjectAcrossDeclarationAndApprovalDocuments() {
        var declaration=file(row("projectCode","P1","subjectCode","S1","subjectName","办公设备","declaredAmount","31200元"));
        var approval=file(row("projectCode","P1","subjectCode","S1","subjectName","办公设备","approvalAmount","3.276万元"));
        var line=first(declaration,approval);assertThat(line.status()).isEqualTo("WITHIN_APPROVAL");assertThat(line.sources()).extracting(Evidence::taskId).contains(declaration.taskId(),approval.taskId());
        assertThat(first(approval,declaration).declaredAmount()).isEqualByComparingTo("31200");
    }
    @Test void onlyApprovalAndPartialSubjectAreRetainedWithoutInventingDeclaration() {
        var only=file(row("subjectName","设备","approvalAmount","100"),row("subjectName","培训"));
        var result=engine.analyze(List.of(only)).get(only.taskId());assertThat(result.lines()).hasSize(2).allSatisfy(l->assertThat(l.status()).isEqualTo("INSUFFICIENT"));assertThat(result.lines().get(0).approvalAmount()).isEqualByComparingTo("100");
    }
    @Test void neverUsesAnotherProjectOrAnotherSubject() {
        var declaration=file(row("projectCode","P1","subjectCode","S1","declaredAmount","100"));
        var project=file(row("projectCode","P2","subjectCode","S1","approvalAmount","200"));
        var subject=file(row("projectCode","P1","subjectCode","S2","approvalAmount","200"));
        assertThat(first(declaration,project,subject).approvalAmount()).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"budgetYear","departmentName"})
    void explicitContextConflictAndMissingContextCannotBridgeTwoVersions(String field) {
        var declaration=file(row("projectCode","P","subjectCode","S","declaredAmount","100",field,"A"));
        var other=file(row("projectCode","P","subjectCode","S","approvalAmount","200",field,"B"));
        assertThat(first(declaration,other).approvalAmount()).isNull();
        var unknown=file(row("projectCode","P","subjectCode","S","declaredAmount","100"));
        var version=file(row("projectCode","P","subjectCode","S","approvalAmount","300",field,"A"));
        assertThat(first(unknown,version,other).status()).isEqualTo("CONFLICT");
    }
    @Test void doesNotAssociateAnonymousSubjectsByFileProximity() {
        var declaration=file(row("subjectName","设备","declaredAmount","100"));
        var approval=file(row("subjectName","设备","approvalAmount","200"));
        assertThat(first(declaration,approval).status()).isEqualTo("INSUFFICIENT");
    }
    @Test void conflictingApprovalVersionsAreNotSelectedOrSummed() {
        var d=file(row("projectCode","P","subjectCode","S","declaredAmount","100"));
        var a=file(row("projectCode","P","subjectCode","S","approvalAmount","200"));
        var b=file(row("projectCode","P","subjectCode","S","approvalAmount","300"));
        var line=first(d,a,b);assertThat(line.status()).isEqualTo("CONFLICT");assertThat(line.approvalAmount()).isNull();
    }
    @Test void duplicatesAndScaleDoNotDoubleCountAndZeroIsValid() {
        var d=file(row("projectCode","P","subjectCode","S","declaredAmount","0"));
        var a=file(row("projectCode","P","subjectCode","S","approvalAmount","0.00"));
        var b=file(row("projectCode","P","subjectCode","S","approvalAmount","0"));
        assertThat(first(d,a,b).status()).isEqualTo("WITHIN_APPROVAL");
        assertThat(first(d,a,b).approvalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }
    @ParameterizedTest @ValueSource(strings={"-100","0.001","10000000000000000","100美元","1e3","未批复"})
    void invalidAmountIsNotRoundedOrMadeIntoPassed(String amount) {
        assertThat(first(file(row("subjectName","设备","declaredAmount","100","approvalAmount",amount))).status()).isEqualTo("INVALID_AMOUNT");
    }
    @Test void excessIsComputedExactlyAndUnrelatedDatabaseMappingsCannotChangeIt() {
        var line=first(file(row("subjectName","设备","declaredAmount","100.01","approvalAmount","100.00")));
        assertThat(line.status()).isEqualTo("OVER_APPROVAL");assertThat(line.opinion()).contains("0.01 元");
    }
    @Test void explicitHeadingScopesOnlyItsDocumentAndUnknownRemainsUnknown() {
        var d=file(row("projectName","项目甲","budgetYear","2026"),row("subjectName","设备","declaredAmount","100"));
        var a=file(row("projectName","项目甲","budgetYear","2026"),row("subjectName","设备","approvalAmount","200"));
        var unknown=new Input(UUID.randomUUID(),"待确认材料","UNKNOWN",null);
        assertThat(first(d,a,unknown).status()).isEqualTo("INSUFFICIENT");
        var results=engine.analyze(List.of(d,a,unknown));assertThat(results.get(d.taskId()).summary()).contains("依据尚不完整");assertThat(results.get(unknown.taskId()).summary()).contains("不能视为没有");
    }
    @Test void rowSpecificProjectMustNotScopeAnotherAnonymousRow() {
        var d=file(row("projectCode","P","subjectCode","S","declaredAmount","100"),row("subjectCode","S","declaredAmount","150"));
        var a=file(row("projectCode","P","subjectCode","S","approvalAmount","200"));
        var result=engine.analyze(List.of(d,a)).get(d.taskId());assertThat(result.lines().get(1).status()).isEqualTo("INSUFFICIENT");
    }
    @Test void paragraphSeparatedSingleSubjectHeadingScopesItsOwnAmounts() {
        var input=file(row("projectCode","P1"),row("subjectName","设备"),row("declaredAmount","31200"),row("approvalAmount","32760"));
        var lines=engine.analyze(List.of(input)).get(input.taskId()).lines();
        assertThat(lines).hasSize(1);assertThat(lines.get(0).status()).isEqualTo("WITHIN_APPROVAL");
        var anonymous=file(row("subjectName","设备"),row("declaredAmount","100"),row("approvalAmount","200"));
        assertThat(first(anonymous).status()).isEqualTo("WITHIN_APPROVAL");
    }
    @Test void differentProjectRowCannotBorrowTheHeadingsSubjectOrYear() {
        var input=file(row("projectCode","P1","subjectName","设备","budgetYear","2026"),row("projectCode","P2","declaredAmount","100","approvalAmount","200"));
        var lines=engine.analyze(List.of(input)).get(input.taskId()).lines();
        assertThat(lines.get(1).subject()).isNull();assertThat(lines.get(1).year()).isNull();assertThat(lines.get(1).status()).isEqualTo("INSUFFICIENT");
    }
    @Test void multipleSubjectHeadingsCannotBeAssignedToUnlabelledAmounts() {
        var input=file(row("subjectName","设备"),row("subjectName","培训"),row("declaredAmount","100"),row("approvalAmount","200"));
        assertThat(engine.analyze(List.of(input)).get(input.taskId()).lines()).noneSatisfy(line->assertThat(line.status()).isEqualTo("WITHIN_APPROVAL"));
    }
    @ParameterizedTest @ValueSource(strings={"项目预算金额","采购预算金额","项目预算总额","项目总预算","预算总金额","预算总额","预算金额","单价","合计","总计"})
    void exactProjectTotalMisclassifiedByModelDoesNotContaminateActualLineAmounts(String label) {
        var actual=row("projectCode","P","subjectName","设备","declaredAmount","31200","approvalAmount","32760");
        var total=new Row("total",List.of(new Fact("declaredAmount","200000元","total",label+"：200000元")));
        var input=file(row("projectCode","P","subjectName","设备"),actual,total);
        var result=engine.analyze(List.of(input)).get(input.taskId());
        assertThat(result.lines()).hasSize(1);assertThat(result.lines().get(0).declaredAmount()).isEqualByComparingTo("31200");assertThat(result.lines().get(0).status()).isEqualTo("WITHIN_APPROVAL");
        assertThat(result.summary()).contains("项目总预算");
    }
    @Test void ambiguousParagraphAndGenuineConflictingDeclarationRemainConflicts() {
        var first=row("projectCode","P","subjectName","设备","declaredAmount","100","approvalAmount","200");
        var second=new Row("other",List.of(new Fact("projectCode","P","p","P"),new Fact("subjectName","设备","p","设备"),
            new Fact("declaredAmount","300","p","预算金额300，另有申报金额100")));
        assertThat(first(file(first,second)).status()).isEqualTo("CONFLICT");
    }

    @Test void repeatedWorkbookSheetsRetainVerifiedSingleAmountsWithoutInventingTotals() {
        var rows=new ArrayList<Row>();
        for(int sheet=1;sheet<=2;sheet++) {
            String locator="excel/sheet:"+sheet+"/row:12/part:"+(sheet*12);
            rows.add(new Row(locator,List.of(new Fact("projectCode","P",locator,"P"),
                    new Fact("subjectName","设备",locator,"设备"),new Fact("supplierName","供应商甲",locator,"供应商甲"),
                    new Fact("declaredAmount","31200",locator+"/col:2","申报金额：31200"),
                    new Fact("approvalAmount","32760",locator+"/col:3","批复参考金额：32760"))));
        }
        var line=first(file(rows.toArray(Row[]::new)));
        assertThat(line.declaredAmount()).isEqualByComparingTo("31200");
        assertThat(line.approvalAmount()).isEqualByComparingTo("32760");
        assertThat(line.status()).isEqualTo("INSUFFICIENT");
        assertThat(line.opinion()).contains("单条识别金额","未自动相加");
        assertThat(line.sources()).filteredOn(e->e.field().equals("declaredAmount")).hasSize(2);
    }
}
