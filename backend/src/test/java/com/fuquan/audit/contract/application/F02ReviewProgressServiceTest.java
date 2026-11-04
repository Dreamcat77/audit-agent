package com.fuquan.audit.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.contract.infra.memory.LocalContractFixtures;
import com.fuquan.audit.review.application.FindingAcknowledgementService;
import com.fuquan.audit.review.application.FindingAcknowledgementView;
import com.fuquan.audit.review.application.port.AuditCheckResultRepository;
import com.fuquan.audit.review.application.port.AuditTaskRepository;
import com.fuquan.audit.review.domain.AuditCheckResult;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import com.fuquan.audit.review.domain.CheckStatus;
import com.fuquan.audit.review.domain.RiskLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class F02ReviewProgressServiceTest {

    private static final UUID CONTRACT_ID = LocalContractFixtures.PASS_CONTRACT_ID;
    private static final UUID TASK_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Instant STARTED_AT = Instant.parse("2026-08-25T06:00:00Z");
    private static final Instant RESULT_AT = Instant.parse("2026-08-25T06:00:10Z");

    @Test
    void derivesTheReadOnlySourceItemFromOnlyTheRealTaskStartTime() {
        Fixture fixture = fixture(List.of(), List.of(), task(TASK_ID, "CONTRACT", CONTRACT_ID,
                ContractReviewService.REVIEW_TYPE, STARTED_AT));

        F02ReviewProgress progress = fixture.service.getProgress(CONTRACT_ID, TASK_ID);

        assertThat(progress.total()).isEqualTo(1);
        assertThat(progress.riskSummary()).isEqualTo("无风险");
        assertThat(progress.items()).containsExactly(new F02ReviewProgressItem(
                "SOURCE_PREPARATION",
                null,
                "读取合同及比对来源",
                0,
                "DONE",
                null,
                STARTED_AT,
                null));
        verify(fixture.results, never()).appendAll(anyList());
        verify(fixture.tasks, never()).create(any());
        verify(fixture.tasks, never()).transition(any(), any(), anyLong(), any(), any());
        verify(fixture.acknowledgements, never()).acknowledge(any(), any());
        verify(fixture.contractReviewService, never()).review(any());
    }

    @Test
    void leavesTheSourceTimeNullWhenTheTaskHasNoProvableStartTime() {
        Fixture fixture = fixture(List.of(), List.of(), task(TASK_ID, "CONTRACT", CONTRACT_ID,
                ContractReviewService.REVIEW_TYPE, null));

        assertThat(fixture.service.getProgress(CONTRACT_ID, TASK_ID).items())
                .first()
                .extracting(F02ReviewProgressItem::completedAt)
                .isNull();
    }

    @Test
    void mapsAllRuleTitlesAndSortsIndependentlyOfRepositoryOrder() {
        AuditCheckResult words = finding("00000000-0000-0000-0000-000000000010",
                F02RuleCodes.AMOUNT_WORDS_MATCH, null, CheckStatus.PASS, RiskLevel.GREEN);
        AuditCheckResult lineNull = finding("00000000-0000-0000-0000-000000000020",
                F02RuleCodes.LINE_ITEM_ARITHMETIC, null, CheckStatus.PASS, RiskLevel.GREEN);
        AuditCheckResult lineASecond = finding("00000000-0000-0000-0000-000000000022",
                F02RuleCodes.LINE_ITEM_ARITHMETIC, "ITEM-A", CheckStatus.PASS, RiskLevel.GREEN);
        AuditCheckResult lineAFirst = finding("00000000-0000-0000-0000-000000000021",
                F02RuleCodes.LINE_ITEM_ARITHMETIC, "ITEM-A", CheckStatus.PASS, RiskLevel.GREEN);
        AuditCheckResult sum = finding("00000000-0000-0000-0000-000000000030",
                F02RuleCodes.LINE_SUM_MATCH, null, CheckStatus.PASS, RiskLevel.GREEN);
        AuditCheckResult awardAmount = finding("00000000-0000-0000-0000-000000000040",
                F02RuleCodes.AWARD_AMOUNT_MATCH, null, CheckStatus.FAIL, RiskLevel.RED);
        AuditCheckResult awardSupplier = finding("00000000-0000-0000-0000-000000000050",
                F02RuleCodes.AWARD_SUPPLIER_MATCH, null, CheckStatus.PASS, RiskLevel.GREEN);
        AuditCheckResult unknownB = finding("00000000-0000-0000-0000-000000000061",
                "F02_UNKNOWN_B", null, CheckStatus.NOT_EVALUATED, RiskLevel.YELLOW);
        AuditCheckResult unknownA = finding("00000000-0000-0000-0000-000000000060",
                "F02_UNKNOWN_A", null, CheckStatus.PASS, RiskLevel.GREEN);
        List<AuditCheckResult> scrambled = List.of(
                unknownB, lineASecond, awardSupplier, sum, lineNull,
                unknownA, words, awardAmount, lineAFirst);
        List<AuditCheckResult> reversed = new ArrayList<>(scrambled);
        Collections.reverse(reversed);
        Fixture fixture = fixture(scrambled, List.of(), task());
        when(fixture.results.findByTaskId(TASK_ID)).thenReturn(scrambled).thenReturn(reversed);

        F02ReviewProgress first = fixture.service.getProgress(CONTRACT_ID, TASK_ID);
        F02ReviewProgress second = fixture.service.getProgress(CONTRACT_ID, TASK_ID);

        assertThat(first).isEqualTo(second);
        assertThat(first.total()).isEqualTo(10);
        assertThat(first.items().subList(1, first.items().size()))
                .extracting(F02ReviewProgressItem::checkResultId)
                .containsExactly(
                        words.id(), lineNull.id(), lineAFirst.id(), lineASecond.id(), sum.id(),
                        awardAmount.id(), awardSupplier.id(), unknownA.id(), unknownB.id());
        assertThat(first.items().subList(1, first.items().size()))
                .extracting(F02ReviewProgressItem::title)
                .containsExactly(
                        "金额大小写一致性",
                        "明细数量与单价计算",
                        "明细数量与单价计算",
                        "明细数量与单价计算",
                        "明细合计与合同金额",
                        "合同金额与中标金额",
                        "合同乙方与中标供应商",
                        "F02_UNKNOWN_A",
                        "F02_UNKNOWN_B");
        assertThat(first.items()).extracting(F02ReviewProgressItem::displayOrder)
                .containsExactlyElementsOf(IntStream.range(0, 10).boxed().toList());
        verify(fixture.results, never()).appendAll(anyList());
        verify(fixture.tasks, never()).transition(any(), any(), anyLong(), any(), any());
        verify(fixture.acknowledgements, never()).acknowledge(any(), any());
    }

    @Test
    void projectsRealFindingTimesAndJoinsAcknowledgementsByRealResultId() {
        AuditCheckResult acknowledgedResult = finding(
                "00000000-0000-0000-0000-000000000071",
                F02RuleCodes.AWARD_AMOUNT_MATCH,
                null,
                CheckStatus.FAIL,
                RiskLevel.RED);
        AuditCheckResult unacknowledgedResult = finding(
                "00000000-0000-0000-0000-000000000072",
                F02RuleCodes.AWARD_SUPPLIER_MATCH,
                null,
                CheckStatus.PASS,
                RiskLevel.GREEN);
        FindingAcknowledgementView acknowledgement = acknowledgement(acknowledgedResult.id());
        Fixture fixture = fixture(
                List.of(unacknowledgedResult, acknowledgedResult),
                List.of(acknowledgement),
                task());

        F02ReviewProgress progress = fixture.service.getProgress(CONTRACT_ID, TASK_ID);

        assertThat(progress.total()).isEqualTo(3);
        F02ReviewProgressItem riskItem = progress.items().get(1);
        assertThat(riskItem.type()).isEqualTo("FINDING");
        assertThat(riskItem.checkResultId()).isEqualTo(acknowledgedResult.id());
        assertThat(riskItem.status()).isEqualTo("FAIL");
        assertThat(riskItem.risk()).isEqualTo("RED");
        assertThat(riskItem.completedAt()).isEqualTo(acknowledgedResult.createdAt());
        assertThat(riskItem.acknowledgement()).isEqualTo(acknowledgement);
        assertThat(progress.items().get(2).acknowledgement()).isNull();
    }

    @Test
    void summarizesDoneRiskAsIncompleteUntilTheDurableAcknowledgementExists() {
        AuditCheckResult risk = finding(
                "00000000-0000-0000-0000-000000000073",
                F02RuleCodes.AWARD_AMOUNT_MATCH,
                null,
                CheckStatus.FAIL,
                RiskLevel.RED);
        Fixture pending = fixture(List.of(risk), List.of(), task());

        assertThat(pending.service.summarize(CONTRACT_ID, task())).isEqualTo(
                new F02ReviewProgressSummary(2, 2, false, true, 1, "低风险"));

        Fixture acknowledged = fixture(List.of(risk), List.of(acknowledgement(risk.id())), task());
        assertThat(acknowledged.service.summarize(CONTRACT_ID, task())).isEqualTo(
                new F02ReviewProgressSummary(2, 2, true, false, 1, "低风险"));
    }

    @Test
    void pendingTaskNeverLooksCompleteEvenWhenNoFindingExistsYet() {
        AuditTask pendingTask = new AuditTask(
                TASK_ID, "CONTRACT", CONTRACT_ID, 1, ContractReviewService.REVIEW_TYPE,
                AuditTaskStatus.PENDING, "creator", "request-id", "trace-id",
                STARTED_AT.minusSeconds(1), null, null, 0);
        Fixture fixture = fixture(List.of(), List.of(), pendingTask);

        assertThat(fixture.service.summarize(CONTRACT_ID, pendingTask)).isEqualTo(
                new F02ReviewProgressSummary(0, 1, false, false, 0, "无风险"));
    }

    @ParameterizedTest
    @CsvSource({
        "0,无风险",
        "1,低风险",
        "2,低风险",
        "3,高风险"
    })
    void summarizesOnlyNonGreenFindings(int riskCount, String expectedSummary) {
        List<AuditCheckResult> findings = IntStream.range(0, riskCount)
                .mapToObj(index -> finding(
                        String.format("00000000-0000-0000-0000-%012d", 100 + index),
                        "F02_RISK_" + index,
                        null,
                        CheckStatus.FAIL,
                        RiskLevel.RED))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        findings.add(finding(
                "00000000-0000-0000-0000-000000000199",
                F02RuleCodes.AMOUNT_WORDS_MATCH,
                null,
                CheckStatus.PASS,
                RiskLevel.GREEN));
        Fixture fixture = fixture(findings, List.of(), task());

        F02ReviewProgress progress = fixture.service.getProgress(CONTRACT_ID, TASK_ID);

        assertThat(progress.riskSummary()).isEqualTo(expectedSummary);
        assertThat(progress.total()).isEqualTo(riskCount + 2);
    }

    @Test
    void rejectsMissingOrMismatchedContractAndTaskSemanticIdentitiesAsNotFound() {
        Fixture missingContract = fixture(List.of(), List.of(), task());
        when(missingContract.contractReviewService.getContract(CONTRACT_ID))
                .thenThrow(new BusinessException(BusinessErrorCode.NOT_FOUND));
        assertNotFound(() -> missingContract.service.getProgress(CONTRACT_ID, TASK_ID));

        Fixture wrongContract = fixture(List.of(), List.of(), task());
        when(wrongContract.contractReviewService.getContract(CONTRACT_ID))
                .thenReturn(LocalContractFixtures.contracts().stream()
                        .filter(contract -> !contract.id().equals(CONTRACT_ID))
                        .findFirst()
                        .orElseThrow());
        assertNotFound(() -> wrongContract.service.getProgress(CONTRACT_ID, TASK_ID));

        Fixture missingTask = fixture(List.of(), List.of(), task());
        when(missingTask.tasks.findById(TASK_ID)).thenReturn(Optional.empty());
        assertNotFound(() -> missingTask.service.getProgress(CONTRACT_ID, TASK_ID));

        assertTaskMismatchNotFound(task(
                UUID.fromString("20000000-0000-0000-0000-000000000099"),
                "CONTRACT", CONTRACT_ID, ContractReviewService.REVIEW_TYPE, STARTED_AT));
        assertTaskMismatchNotFound(task(TASK_ID, "REVIEW_SESSION", CONTRACT_ID,
                ContractReviewService.REVIEW_TYPE, STARTED_AT));
        assertTaskMismatchNotFound(task(TASK_ID, "CONTRACT", UUID.randomUUID(),
                ContractReviewService.REVIEW_TYPE, STARTED_AT));
        assertTaskMismatchNotFound(task(TASK_ID, "CONTRACT", CONTRACT_ID,
                "AI_REVIEW", STARTED_AT));
    }

    @Test
    void rejectsARepositoryResultWhoseRealTaskIdentityDoesNotMatch() {
        AuditCheckResult alienResult = new AuditCheckResult(
                UUID.fromString("00000000-0000-0000-0000-000000000299"),
                UUID.fromString("20000000-0000-0000-0000-000000000299"),
                F02RuleCodes.AMOUNT_WORDS_MATCH,
                null,
                CheckStatus.PASS,
                RiskLevel.GREEN,
                false,
                "actual",
                "expected",
                "detail",
                1,
                RESULT_AT);
        Fixture fixture = fixture(List.of(alienResult), List.of(), task());

        assertNotFound(() -> fixture.service.getProgress(CONTRACT_ID, TASK_ID));
    }

    private void assertTaskMismatchNotFound(AuditTask mismatchedTask) {
        Fixture fixture = fixture(List.of(), List.of(), mismatchedTask);
        assertNotFound(() -> fixture.service.getProgress(CONTRACT_ID, TASK_ID));
    }

    private Fixture fixture(
            List<AuditCheckResult> findings,
            List<FindingAcknowledgementView> acknowledgementViews,
            AuditTask task) {
        ContractReviewService contractReviewService = mock(ContractReviewService.class);
        AuditTaskRepository tasks = mock(AuditTaskRepository.class);
        AuditCheckResultRepository results = mock(AuditCheckResultRepository.class);
        FindingAcknowledgementService acknowledgements = mock(FindingAcknowledgementService.class);
        Contract contract = LocalContractFixtures.contracts().stream()
                .filter(candidate -> candidate.id().equals(CONTRACT_ID))
                .findFirst()
                .orElseThrow();
        when(contractReviewService.getContract(CONTRACT_ID)).thenReturn(contract);
        when(tasks.findById(TASK_ID)).thenReturn(Optional.of(task));
        when(results.findByTaskId(TASK_ID)).thenReturn(findings);
        when(acknowledgements.findByTaskId(TASK_ID)).thenReturn(acknowledgementViews);
        return new Fixture(
                new F02ReviewProgressService(
                        contractReviewService, tasks, results, acknowledgements),
                contractReviewService,
                tasks,
                results,
                acknowledgements);
    }

    private AuditTask task() {
        return task(TASK_ID, "CONTRACT", CONTRACT_ID,
                ContractReviewService.REVIEW_TYPE, STARTED_AT);
    }

    private AuditTask task(
            UUID id,
            String sourceType,
            UUID sourceId,
            String reviewType,
            Instant startedAt) {
        return new AuditTask(
                id,
                sourceType,
                sourceId,
                1,
                reviewType,
                AuditTaskStatus.DONE,
                "creator",
                "request-id",
                "trace-id",
                STARTED_AT.minusSeconds(1),
                startedAt,
                RESULT_AT,
                2);
    }

    private AuditCheckResult finding(
            String id,
            String ruleCode,
            String itemKey,
            CheckStatus status,
            RiskLevel risk) {
        return new AuditCheckResult(
                UUID.fromString(id),
                TASK_ID,
                ruleCode,
                itemKey,
                status,
                risk,
                risk == RiskLevel.RED,
                "actual",
                "expected",
                "detail",
                1,
                RESULT_AT.plusMillis(UUID.fromString(id).getLeastSignificantBits()));
    }

    private FindingAcknowledgementView acknowledgement(UUID resultId) {
        return new FindingAcknowledgementView(
                UUID.fromString("30000000-0000-0000-0000-000000000001"),
                resultId,
                "actor-1",
                "审查员",
                RESULT_AT.plusSeconds(1),
                "ack-request",
                "ack-trace",
                1);
    }

    private void assertNotFound(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo(BusinessErrorCode.NOT_FOUND));
    }

    private record Fixture(
            F02ReviewProgressService service,
            ContractReviewService contractReviewService,
            AuditTaskRepository tasks,
            AuditCheckResultRepository results,
            FindingAcknowledgementService acknowledgements) {
    }
}
