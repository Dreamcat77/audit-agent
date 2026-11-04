package com.fuquan.audit.contract.api.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisPort;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisResult;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.contract.application.port.ContractRepository;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.contract.domain.Money;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepAttempt;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ReviewStepExternalCallState;
import com.fuquan.audit.contract.infra.memory.reviewrun.InMemoryContractReviewRunRepositories;
import com.fuquan.audit.contract.infra.memory.reviewrun.InMemoryContractReviewResultsRepositories;
import com.fuquan.audit.contract.infra.memory.LocalContractStore;
import com.fuquan.audit.security.domain.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class ContractReviewRunApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired InMemoryIdempotencyAdapter idempotency;
    @Autowired InMemoryContractReviewRunRepositories reviewRuns;
    @Autowired InMemoryContractReviewResultsRepositories reviewResults;
    @Autowired ContractRepository contracts;
    @Autowired LocalContractStore contractStore;
    @MockBean ContractTextAnalysisPort semanticAnalysis;
    @MockBean BudgetSemanticAnalysisPort budgetSemanticAnalysis;

    @Test
    void completePromotedContractReopensFrozenFactsAndSixPassingF02Results() throws Exception {
        Authentication actor = auth();
        Source source = promotedCompleteContract(actor);
        Contract durableContract = contracts.findById(UUID.fromString(source.contractId())).orElseThrow();
        assertThat(durableContract.lineItems()).extracting(item -> item.itemKey())
                .containsExactly("ITEM-1", "ITEM-2");
        assertThat(durableContract.amountInWords()).isEqualTo("壹佰元整");
        assertThat(durableContract.contractAmount().amount())
                .isEqualByComparingTo(durableContract.awardNotice().winningAmount().amount());
        String runPath = "/api/v1/projects/{projectId}/contracts/{contractId}/review-runs";
        String created = mvc.perform(post(runPath, source.projectId(), source.contractId())
                        .header("Idempotency-Key", "complete-f02-run-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedContractVersion\":0,\"materialFileIds\":[\"%s\"]}"
                                .formatted(source.fileId()))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String runId = JsonPath.read(created, "$.data.runId");
        String stepId = stepId(created, "F02_DETERMINISTIC");
        String frozenHash = JsonPath.read(created, "$.data.contractSnapshotSha256");
        String frozen = JsonPath.read(created, "$.data.contractSnapshotCanonicalJson");
        List<Map<String, Object>> lines = JsonPath.read(frozen, "$.lineItems");
        assertThat(lines).extracting(line -> line.get("itemKey"))
                .containsExactly("ITEM-1", "ITEM-2");
        assertThat(new BigDecimal(lines.get(0).get("quantity").toString()))
                .isEqualByComparingTo("2");
        assertThat(new BigDecimal(lines.get(0).get("unitPrice").toString()))
                .isEqualByComparingTo("20.00");
        assertThat(new BigDecimal(lines.get(0).get("declaredAmount").toString()))
                .isEqualByComparingTo("40.00");
        assertThat(new BigDecimal(lines.get(1).get("quantity").toString()))
                .isEqualByComparingTo("3");
        assertThat(new BigDecimal(lines.get(1).get("unitPrice").toString()))
                .isEqualByComparingTo("20.00");
        assertThat(new BigDecimal(lines.get(1).get("declaredAmount").toString()))
                .isEqualByComparingTo("60.00");
        assertThat(new BigDecimal(JsonPath.<String>read(frozen, "$.contractAmount")))
                .isEqualByComparingTo("100.00");
        assertThat(JsonPath.<String>read(frozen, "$.amountInWords")).isEqualTo("壹佰元整");
        assertThat(new BigDecimal(JsonPath.<String>read(frozen, "$.awardNotice.winningAmount")))
                .isEqualByComparingTo("100.0");
        assertThat(JsonPath.<String>read(frozen, "$.awardNotice.winningBidder"))
                .isEqualTo("Supplier");

        String reopenedRun = mvc.perform(get(runPath + "/{runId}",
                        source.projectId(), source.contractId(), runId)
                        .with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(reopenedRun, "$.data.contractSnapshotCanonicalJson"))
                .isEqualTo(frozen);
        assertThat(JsonPath.<String>read(reopenedRun, "$.data.contractSnapshotSha256"))
                .isEqualTo(frozenHash);
        mvc.perform(get(runPath + "/{runId}", source.projectId(), source.contractId(), runId)
                        .with(authentication(auth("LEGAL", "other-review-user"))))
                .andExpect(status().isNotFound());

        String executed = executeStep(actor, source, runId, stepId, "complete-f02-step-" + UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.step.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        String taskId = JsonPath.read(executed, "$.data.auditTask.id");
        List<Map<String, Object>> results = JsonPath.read(executed, "$.data.auditCheckResults");
        assertThat(results).hasSize(6).allSatisfy(result -> {
            assertThat(result.get("checkStatus")).isEqualTo("PASS");
            assertThat(result.get("taskId")).isEqualTo(taskId);
        });
        assertThat(results).extracting(result -> result.get("ruleCode"))
                .containsExactlyInAnyOrder(F02RuleCodes.AMOUNT_WORDS_MATCH,
                        F02RuleCodes.LINE_ITEM_ARITHMETIC, F02RuleCodes.LINE_ITEM_ARITHMETIC,
                        F02RuleCodes.LINE_SUM_MATCH, F02RuleCodes.AWARD_AMOUNT_MATCH,
                        F02RuleCodes.AWARD_SUPPLIER_MATCH);
        assertThat(results.stream()
                .filter(result -> F02RuleCodes.LINE_ITEM_ARITHMETIC.equals(result.get("ruleCode")))
                .map(result -> result.get("itemKey")).toList())
                .containsExactlyInAnyOrder("ITEM-1", "ITEM-2");

        mvc.perform(get("/api/v1/contract-audit-tasks")
                        .param("keyword", durableContract.contractNo())
                        .param("includeHistory", "true")
                        .with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].taskId").value(taskId))
                .andExpect(jsonPath("$.data.items[0].reviewRun.kind").value("INTEGRATED"))
                .andExpect(jsonPath("$.data.items[0].reviewRun.projectId").value(source.projectId()))
                .andExpect(jsonPath("$.data.items[0].reviewRun.runId").value(runId));

        String reopenedStep = mvc.perform(get(runPath + "/{runId}/steps/{stepId}",
                        source.projectId(), source.contractId(), runId, stepId)
                        .with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(reopenedStep, "$.data.auditTask.id")).isEqualTo(taskId);
        List<Map<String, Object>> reopenedResults = JsonPath.read(reopenedStep, "$.data.auditCheckResults");
        assertThat(reopenedResults).extracting(result -> result.get("id") + ":" + result.get("checkStatus"))
                .containsExactlyInAnyOrderElementsOf(results.stream()
                        .map(result -> result.get("id") + ":" + result.get("checkStatus")).toList());
        assertThat(JsonPath.<String>read(reopenedStep, "$.data.run.contractSnapshotSha256"))
                .isEqualTo(frozenHash);
        mvc.perform(get(runPath + "/{runId}/steps/{stepId}",
                        source.projectId(), source.contractId(), runId, stepId)
                        .with(authentication(auth("LEGAL", "other-review-user"))))
                .andExpect(status().isNotFound());

        String secondCreated = mvc.perform(post(runPath, source.projectId(), source.contractId())
                        .header("Idempotency-Key", "second-f02-run-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedContractVersion\":0,\"materialFileIds\":[\"%s\"]}"
                                .formatted(source.fileId()))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String secondRunId = JsonPath.read(secondCreated, "$.data.runId");
        String secondStepId = stepId(secondCreated, "F02_DETERMINISTIC");
        String secondExecuted = executeStep(actor, source, secondRunId, secondStepId,
                "second-f02-step-" + UUID.randomUUID())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String secondTaskId = JsonPath.read(secondExecuted, "$.data.auditTask.id");
        assertThat(secondRunId).isNotEqualTo(runId);
        assertThat(secondTaskId).isNotEqualTo(taskId);
        int durableOperationsBeforeReopen = idempotency.size();
        String history = mvc.perform(get("/api/v1/contract-audit-tasks")
                        .param("keyword", durableContract.contractNo())
                        .param("includeHistory", "true")
                        .with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> historicalTasks = JsonPath.read(history, "$.data.items");
        assertThat(historicalTasks).hasSize(2);
        assertThat(historicalTasks).anySatisfy(row -> {
            assertThat(row.get("taskId")).isEqualTo(taskId);
            assertThat(JsonPath.<String>read(row, "$.reviewRun.runId")).isEqualTo(runId);
        }).anySatisfy(row -> {
            assertThat(row.get("taskId")).isEqualTo(secondTaskId);
            assertThat(JsonPath.<String>read(row, "$.reviewRun.runId")).isEqualTo(secondRunId);
        });
        String oldAfterSecondRun = mvc.perform(get(runPath + "/{runId}",
                        source.projectId(), source.contractId(), runId)
                        .with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(oldAfterSecondRun, "$.data.contractSnapshotSha256"))
                .isEqualTo(frozenHash);
        assertThat(JsonPath.<List<String>>read(oldAfterSecondRun,
                "$.data.outputs[?(@.step.stepType == 'F02_DETERMINISTIC')].auditTask.id"))
                .containsExactly(taskId);
        assertThat(idempotency.size()).isEqualTo(durableOperationsBeforeReopen);
    }

    @Test
    void amountOnlyContractFreezesExecutesAndReopensWithoutTechnicalNotEvaluatedLeakage() throws Exception {
        Authentication actor = auth();
        String payload = """
                {"expectedVersion":0,"contractNo":"RUN-%s","contractName":"Amount Only Contract",
                 "contractType":"SERVICES","partyA":"Buyer","partyB":"Supplier",
                 "contractAmount":67000.00,"amountInWords":"人民币陆万柒仟元整",
                 "lineItems":[
                   {"itemKey":"S-1","itemName":"Service 1","basis":"AMOUNT_ONLY","declaredAmount":28000.00,"sortNo":1},
                   {"itemKey":"S-2","itemName":"Service 2","basis":"AMOUNT_ONLY","declaredAmount":16000.00,"sortNo":2},
                   {"itemKey":"S-3","itemName":"Service 3","basis":"AMOUNT_ONLY","declaredAmount":9000.00,"sortNo":3},
                   {"itemKey":"S-4","itemName":"Service 4","basis":"AMOUNT_ONLY","declaredAmount":14000.00,"sortNo":4}]}
                """.formatted(UUID.randomUUID());
        Source source = promotedContract(actor,
                new MockMultipartFile("file", "001.pdf", "application/pdf", pdf()), payload);
        String created = createRun(actor, source);
        String runId = JsonPath.read(created, "$.data.runId");
        String stepId = stepId(created, "F02_DETERMINISTIC");
        String frozen = JsonPath.read(created, "$.data.contractSnapshotCanonicalJson");
        assertThat(JsonPath.<Integer>read(frozen, "$.schemaVersion")).isEqualTo(2);
        assertThat(JsonPath.<List<String>>read(frozen, "$.lineItems[*].basis"))
                .containsOnly("AMOUNT_ONLY");
        assertThat(JsonPath.<List<Object>>read(frozen, "$.lineItems[*].quantity"))
                .containsOnlyNulls();

        String executed = executeStep(actor, source, runId, stepId,
                "amount-only-f02-step-" + UUID.randomUUID())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> results = JsonPath.read(executed, "$.data.auditCheckResults");
        assertThat(results.stream().filter(value -> F02RuleCodes.LINE_ITEM_ARITHMETIC
                .equals(value.get("ruleCode"))).toList()).hasSize(4)
                .allSatisfy(value -> assertThat(value.get("checkStatus")).isEqualTo("NOT_EVALUATED"));
        assertThat(results.stream().filter(value -> F02RuleCodes.LINE_SUM_MATCH
                .equals(value.get("ruleCode"))).toList()).singleElement()
                .satisfies(value -> assertThat(value.get("checkStatus")).isEqualTo("PASS"));

        String reopened = mvc.perform(get(
                        "/api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/steps/{stepId}",
                        source.projectId(), source.contractId(), runId, stepId)
                        .with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(reopened, "$.data.run.contractSnapshotCanonicalJson"))
                .isEqualTo(frozen);
        assertThat(JsonPath.<List<String>>read(reopened,
                "$.data.auditCheckResults[?(@.ruleCode == 'F02_LINE_ITEM_ARITHMETIC')].checkStatus"))
                .containsOnly("NOT_EVALUATED");
    }

    @Test
    void missingAmountWordsLeavesAllOtherFrozenF02RulesCalculable() throws Exception {
        Source source = promotedCompleteContractWithout(auth(), "amountInWords");
        assertFactAbsenceResults(source, "amountInWords",
                List.of(
                        F02RuleCodes.AMOUNT_WORDS_MATCH + ":null:NOT_EVALUATED",
                        F02RuleCodes.LINE_ITEM_ARITHMETIC + ":ITEM-1:PASS",
                        F02RuleCodes.LINE_ITEM_ARITHMETIC + ":ITEM-2:PASS",
                        F02RuleCodes.LINE_SUM_MATCH + ":null:PASS",
                        F02RuleCodes.AWARD_AMOUNT_MATCH + ":null:PASS",
                        F02RuleCodes.AWARD_SUPPLIER_MATCH + ":null:PASS"),
                Map.of(F02RuleCodes.AMOUNT_WORDS_MATCH, "Amount in words is missing or invalid"));
    }

    @Test
    void emptyLineItemsLeaveWordsAndAwardFrozenF02RulesCalculable() throws Exception {
        Source source = promotedCompleteContractWithout(auth(), "lineItems");
        assertFactAbsenceResults(source, "lineItems",
                List.of(
                        F02RuleCodes.AMOUNT_WORDS_MATCH + ":null:PASS",
                        F02RuleCodes.LINE_ITEM_ARITHMETIC + ":null:NOT_EVALUATED",
                        F02RuleCodes.LINE_SUM_MATCH + ":null:NOT_EVALUATED",
                        F02RuleCodes.AWARD_AMOUNT_MATCH + ":null:PASS",
                        F02RuleCodes.AWARD_SUPPLIER_MATCH + ":null:PASS"),
                Map.of(F02RuleCodes.LINE_ITEM_ARITHMETIC, "Contract has no line item basis",
                        F02RuleCodes.LINE_SUM_MATCH, "Contract has no line totals to sum"));
    }

    @Test
    void absentAwardLeavesWordsAndBothLineFrozenF02RulesCalculable() throws Exception {
        Source source = promotedCompleteContractWithout(auth(), "awardNotice");
        assertFactAbsenceResults(source, "awardNotice",
                List.of(
                        F02RuleCodes.AMOUNT_WORDS_MATCH + ":null:PASS",
                        F02RuleCodes.LINE_ITEM_ARITHMETIC + ":ITEM-1:PASS",
                        F02RuleCodes.LINE_ITEM_ARITHMETIC + ":ITEM-2:PASS",
                        F02RuleCodes.LINE_SUM_MATCH + ":null:PASS",
                        F02RuleCodes.AWARD_AMOUNT_MATCH + ":null:NOT_EVALUATED",
                        F02RuleCodes.AWARD_SUPPLIER_MATCH + ":null:NOT_EVALUATED"),
                Map.of(F02RuleCodes.AWARD_AMOUNT_MATCH, "Award notice amount basis is missing",
                        F02RuleCodes.AWARD_SUPPLIER_MATCH, "Award notice supplier basis is missing"));
    }

    private void assertFactAbsenceResults(Source source, String missingField, List<String> expectedRows,
            Map<String, String> missingReasons) throws Exception {
        Authentication actor = auth();
        Contract durableContract = contracts.findById(UUID.fromString(source.contractId())).orElseThrow();
        assertThat(durableContract.contractAmount().amount()).isEqualByComparingTo("100.00");
        assertThat(durableContract.amountInWords())
                .isEqualTo("amountInWords".equals(missingField) ? null : "壹佰元整");
        assertThat(durableContract.lineItems()).hasSize("lineItems".equals(missingField) ? 0 : 2);
        if ("awardNotice".equals(missingField)) {
            assertThat(durableContract.awardNotice()).isNull();
        } else {
            assertThat(durableContract.awardNotice().winningAmount().amount())
                    .isEqualByComparingTo("100.0");
            assertThat(durableContract.awardNotice().winningBidder()).isEqualTo("Supplier");
        }
        String created = createRun(actor, source);
        String runId = JsonPath.read(created, "$.data.runId");
        String stepId = stepId(created, "F02_DETERMINISTIC");
        String frozenHash = JsonPath.read(created, "$.data.contractSnapshotSha256");
        String frozen = JsonPath.read(created, "$.data.contractSnapshotCanonicalJson");
        Map<String, Object> frozenFacts = JsonPath.read(frozen, "$");
        assertThat(frozenFacts.get("amountInWords"))
                .isEqualTo("amountInWords".equals(missingField) ? null : "壹佰元整");
        assertThat((List<?>) frozenFacts.get("lineItems"))
                .hasSize("lineItems".equals(missingField) ? 0 : 2);
        if ("awardNotice".equals(missingField)) {
            assertThat(frozenFacts.get("awardNotice")).isNull();
        } else {
            assertThat(JsonPath.<String>read(frozen, "$.awardNotice.winningBidder"))
                    .isEqualTo("Supplier");
        }
        String reopenedRun = mvc.perform(get("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}",
                        source.projectId(), source.contractId(), runId)
                        .with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(reopenedRun, "$.data.contractSnapshotCanonicalJson"))
                .isEqualTo(frozen);
        assertThat(JsonPath.<String>read(reopenedRun, "$.data.contractSnapshotSha256"))
                .isEqualTo(frozenHash);
        String executed = executeStep(actor, source, runId, stepId,
                "missing-fact-f02-step-" + UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.step.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        String taskId = JsonPath.read(executed, "$.data.auditTask.id");
        List<Map<String, Object>> results = JsonPath.read(executed, "$.data.auditCheckResults");
        assertThat(results).extracting(result -> result.get("ruleCode") + ":"
                        + result.get("itemKey") + ":" + result.get("checkStatus"))
                .containsExactlyInAnyOrderElementsOf(expectedRows);
        assertThat(results).allSatisfy(result -> {
            assertThat(result.get("taskId")).isEqualTo(taskId);
            if (missingReasons.containsKey(result.get("ruleCode"))) {
                assertThat(result.get("detail"))
                        .isEqualTo(missingReasons.get(result.get("ruleCode")));
                assertThat(result.get("actualValue")).isNull();
                assertThat(result.get("expectedValue")).isNull();
            }
        });

        String runPath = "/api/v1/projects/{projectId}/contracts/{contractId}/review-runs";
        String reopened = mvc.perform(get(runPath + "/{runId}/steps/{stepId}",
                        source.projectId(), source.contractId(), runId, stepId)
                        .with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(reopened, "$.data.auditTask.id")).isEqualTo(taskId);
        assertThat(JsonPath.<String>read(reopened, "$.data.run.contractSnapshotSha256"))
                .isEqualTo(frozenHash);
        List<Map<String, Object>> reopenedResults = JsonPath.read(reopened, "$.data.auditCheckResults");
        assertThat(reopenedResults).extracting(result -> result.get("id") + ":"
                        + result.get("ruleCode") + ":" + result.get("checkStatus") + ":"
                        + result.get("detail"))
                .containsExactlyInAnyOrderElementsOf(results.stream()
                        .map(result -> result.get("id") + ":" + result.get("ruleCode") + ":"
                                + result.get("checkStatus") + ":" + result.get("detail"))
                        .toList());
    }

    @Test
    void stepReadReopensManifestAndExecuteEndpointEnforcesEmptyBodyCsrfAndMaterialBoundary() throws Exception {
        Authentication actor = auth();
        var source = promotedContract(actor);
        String runsPath = "/api/v1/projects/{projectId}/contracts/{contractId}/review-runs";
        var created = mvc.perform(post(runsPath, source.projectId(), source.contractId())
                        .header("Idempotency-Key", "step-shell-run-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedContractVersion\":0,\"materialFileIds\":[\"%s\"]}"
                                .formatted(source.fileId()))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isAccepted()).andReturn();
        String runId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.runId");
        String materialStepId = JsonPath.read(created.getResponse().getContentAsString(), "$.data.steps[0].stepId");
        String stepPath = runsPath + "/{runId}/steps/{stepId}";
        mvc.perform(get(stepPath, source.projectId(), source.contractId(), runId, materialStepId)
                        .with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.step.stepType").value("MATERIAL_VALIDATION"))
                .andExpect(jsonPath("$.data.step.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.attempts.length()").value(0));
        int before = idempotency.size();
        mvc.perform(post(stepPath + "/execute", source.projectId(), source.contractId(), runId,
                        materialStepId).header("Idempotency-Key", "material-execute-" + UUID.randomUUID())
                        .contentType("application/json").content("{}")
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isConflict());
        assertThat(idempotency.size()).isEqualTo(before);
        mvc.perform(post(stepPath + "/execute", source.projectId(), source.contractId(), runId,
                        materialStepId).header("Idempotency-Key", "bad-body-" + UUID.randomUUID())
                        .contentType("application/json").content("{\"attemptNo\":1}")
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isBadRequest());
        mvc.perform(post(stepPath + "/execute", source.projectId(), source.contractId(), runId,
                        materialStepId).header("Idempotency-Key", "no-csrf-" + UUID.randomUUID())
                        .contentType("application/json").content("{}")
                        .with(authentication(actor)))
                .andExpect(status().isForbidden());
        mvc.perform(get(stepPath, UUID.randomUUID(), source.contractId(), runId, materialStepId)
                        .with(authentication(actor)))
                .andExpect(status().isNotFound());
    }

    @Test
    void executesAllFrozenStepsPersistsOutputsAndReplaysWithoutCallingModelTwice() throws Exception {
        Authentication actor = auth();
        var source = promotedContract(actor, new MockMultipartFile("file", "001.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docx(List.of("Contract No: EXEC-1", "Contract Name: Synthetic Contract",
                        "Contract Amount: 100.00", "逾期交付每日按合同金额1%，累计违约金不超过10%。",
                        "合同生效日期为2026年09月01日。", "严重违约时甲方有权解除合同。"))));
        when(semanticAnalysis.analyzeContractText(any())).thenReturn(new ContractTextAnalysisResult(
                true, List.of("typed summary"), List.of(new ContractTextAnalysisResult.Hint(
                        ContractTextAnalysisResult.SemanticCategory.CONTRACT_STRUCTURE,
                        ContractTextAnalysisResult.Attention.REVIEW, "typed hint", "typed description",
                        List.of(UUID.fromString(source.fileId())),
                        List.of(new ContractTextAnalysisResult.EvidenceItem(
                                UUID.fromString(source.fileId()),
                                ContractTextAnalysisResult.EvidenceStatus.REVIEW,
                                "合同生效日期为2026年09月01日。", false)))),
                "typed-model-v1", 5));
        String comparisonFileId = addFrozenMaterial(actor, source.projectId(), "comparison.docx",
                docx(List.of("合同条款及格式", "合同金额", "履行期限", "违约责任", "合同生效")),
                "TENDER_DOCUMENT");
        String runsPath = "/api/v1/projects/{projectId}/contracts/{contractId}/review-runs";
        var created = mvc.perform(post(runsPath, source.projectId(), source.contractId())
                        .header("Idempotency-Key", "execute-all-run-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedContractVersion\":0,\"materialFileIds\":[\"%s\",\"%s\"]}"
                                .formatted(source.fileId(), comparisonFileId))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isAccepted()).andReturn();
        String body = created.getResponse().getContentAsString();
        String runId = JsonPath.read(body, "$.data.runId");
        String f02Step = stepId(body, "F02_DETERMINISTIC");
        String semanticStep = stepId(body, "SEMANTIC_AI");
        String breachStep = stepId(body, "BREACH");
        String termStep = stepId(body, "TERM");

        String f02Key = "execute-f02-" + UUID.randomUUID();
        var f02 = executeStep(actor, source, runId, f02Step, f02Key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.step.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.step.outputResourceType")
                        .value(com.fuquan.audit.contract.application.reviewrun.executor
                                .FrozenF02ResultMapper.OUTPUT_RESOURCE_TYPE))
                .andExpect(jsonPath("$.data.auditTask.id").isNotEmpty())
                .andExpect(jsonPath("$.data.auditCheckResults[0].id").isNotEmpty())
                .andExpect(jsonPath("$.data.findings.length()").value(5))
                .andExpect(jsonPath("$.data.findingCheckResultRelations").isMap()).andReturn();
        String taskId = JsonPath.read(f02.getResponse().getContentAsString(), "$.data.auditTask.id");
        executeStep(actor, source, runId, f02Step, f02Key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.auditTask.id").value(taskId));

        String semanticKey = "execute-semantic-" + UUID.randomUUID();
        executeStep(actor, source, runId, semanticStep, semanticKey)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.findings[0].source").value("MODEL_ASSISTED"))
                .andExpect(jsonPath("$.data.findings[0].attentionCode").value("REVIEW"))
                .andExpect(jsonPath("$.data.findings[0].riskLevel").isEmpty())
                .andExpect(jsonPath("$.data.evidence[0].locatorAvailable").value(true));
        executeStep(actor, source, runId, semanticStep, semanticKey)
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true));
        verify(semanticAnalysis, times(1)).analyzeContractText(any());
        executeStep(actor, source, runId, semanticStep, "different-key-" + UUID.randomUUID())
                .andExpect(status().isConflict());

        var breach = executeStep(actor, source, runId, breachStep, "execute-breach-" + UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.findings[0].findingId").isNotEmpty())
                .andExpect(jsonPath("$.data.evidence[0].evidenceId").isNotEmpty())
                .andReturn();
        String breachBody = breach.getResponse().getContentAsString();
        String findingId = JsonPath.read(breachBody, "$.data.findings[0].findingId");
        String evidenceId = JsonPath.read(breachBody, "$.data.evidence[0].evidenceId");
        assertTypedV2(breachBody, "BREACH", "NOT_EVALUATED", 1);
        String termBody = executeStep(actor, source, runId, termStep, "execute-term-" + UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.findings[0].findingId").isNotEmpty())
                .andExpect(jsonPath("$.data.evidence[0].evidenceId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertTypedV2(termBody, "TERM", "NOT_EVALUATED", 2);
        mvc.perform(get(runsPath + "/{runId}", source.projectId(), source.contractId(), runId)
                        .with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.outputs.length()").value(5));
        DurableState beforeLatest = durableState();
        String latest = mvc.perform(get(runsPath + "/latest", source.projectId(), source.contractId())
                        .with(authentication(actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(runId))
                .andExpect(jsonPath("$.data.executionMode").value("CLIENT_COMPATIBILITY"))
                .andExpect(jsonPath("$.data.outputs.length()").value(5))
                .andExpect(jsonPath("$.data.outputs[?(@.step.stepType=='BREACH')].findings[0].findingId")
                        .value(findingId))
                .andExpect(jsonPath("$.data.outputs[?(@.step.stepType=='BREACH')].evidence[0].evidenceId")
                        .value(evidenceId))
                .andReturn().getResponse().getContentAsString();
        List<Integer> latestTypedSchemas = JsonPath.read(latest,
                "$.data.outputs[?(@.step.stepType=='BREACH')].findings[?(@.typedComparison != null)].typedComparison.schemaVersion");
        assertThat(latestTypedSchemas).containsExactly(2);
        assertThat(durableState()).isEqualTo(beforeLatest);
    }

    private static void assertTypedV2(String body, String kind, String result, int expectedCount) {
        List<Map<String, Object>> typed = JsonPath.read(body,
                "$.data.findings[?(@.typedComparison != null)].typedComparison");
        assertThat(typed).hasSize(expectedCount).allSatisfy(value -> {
            assertThat(value.get("kind")).isEqualTo(kind);
            assertThat(value.get("result")).isEqualTo(result);
            assertThat(value.get("schemaVersion")).isEqualTo(2);
            assertThat((List<?>) value.get("values")).hasSize(2);
        });
    }

    @Test
    void latestIsAStaticReadRouteWithTheExistingRolePolicy() throws Exception {
        String path = "/api/v1/projects/{projectId}/contracts/{contractId}/review-runs/latest";
        UUID projectId = UUID.randomUUID();
        UUID contractId = UUID.randomUUID();
        mvc.perform(get(path, projectId, contractId).with(authentication(unauthorizedRole())))
                .andExpect(status().isForbidden());
        for (String role : List.of("SYSTEM_ADMIN", "PROCUREMENT_MANAGER", "AUDITOR", "LEGAL")) {
            mvc.perform(get(path, projectId, contractId).with(authentication(auth(role))))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void latestFiltersByActorAndReturnsOwnedOlderRunInsteadOfForeignNewerRun() throws Exception {
        Authentication owner = auth("AUDITOR", actorId("owner"));
        Authentication foreign = auth("LEGAL", actorId("foreign"));
        Authentication third = auth("SYSTEM_ADMIN", actorId("third"));
        Source source = promotedContract(owner);
        String ownedRunId = JsonPath.read(createRun(owner, source), "$.data.runId");
        String foreignRunId = JsonPath.read(createRun(foreign, source), "$.data.runId");
        String path = "/api/v1/projects/{projectId}/contracts/{contractId}/review-runs/latest";

        mvc.perform(get(path, source.projectId(), source.contractId()).with(authentication(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(ownedRunId));
        assertThat(foreignRunId).isNotEqualTo(ownedRunId);
        mvc.perform(get(path, source.projectId(), source.contractId()).with(authentication(third)))
                .andExpect(status().isNotFound());
    }

    @Test
    void corruptOwnedLatestReturnsConflictWithoutFallingBackToOlderValidRun() throws Exception {
        Authentication actor = auth("AUDITOR", actorId("corrupt"));
        Source source = promotedContract(actor);
        String olderRunId = JsonPath.read(createRun(actor, source), "$.data.runId");
        UUID latestRunId = UUID.fromString(JsonPath.read(createRun(actor, source), "$.data.runId"));
        ContractReviewStepAttempt removed = reviewRuns.findAttemptsByRunId(latestRunId).get(0);
        Map<UUID, ContractReviewStepAttempt> attempts = rows(reviewRuns, "attempts");
        attempts.remove(removed.attemptId());
        try {
            var response = mvc.perform(get(
                            "/api/v1/projects/{projectId}/contracts/{contractId}/review-runs/latest",
                            source.projectId(), source.contractId()).with(authentication(actor)))
                    .andExpect(status().isConflict())
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(olderRunId);
        } finally {
            attempts.put(removed.attemptId(), removed);
        }
    }

    @Test
    void promotedContractCreates202PendingRunAndReopensSameFrozenFiveStepManifest() throws Exception {
        Authentication actor = auth();
        var source = promotedContract(actor);
        String path = "/api/v1/projects/{projectId}/contracts/{contractId}/review-runs";
        String body = "{\"expectedContractVersion\":0,\"materialFileIds\":[\"%s\"]}".formatted(source.fileId());
        String key = "run-api-" + UUID.randomUUID();
        var posted = mvc.perform(post(path, source.projectId(), source.contractId())
                        .header("Idempotency-Key", key).contentType("application/json").content(body)
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.executionMode").value("CLIENT_COMPATIBILITY"))
                .andExpect(jsonPath("$.data.materials.length()").value(1))
                .andExpect(jsonPath("$.data.steps.length()").value(5))
                .andExpect(jsonPath("$.data.attempts.length()").value(4))
                .andExpect(jsonPath("$.data.steps[0].outputResourceType").value("RUN_MATERIAL_SNAPSHOT"))
                .andExpect(jsonPath("$.data.steps[1].outputResourceId").isEmpty())
                .andReturn();
        String runId = JsonPath.read(posted.getResponse().getContentAsString(), "$.data.runId");
        String identity = JsonPath.read(posted.getResponse().getContentAsString(), "$.data.inputSnapshotIdentity");
        int beforeReads = idempotency.size();
        mvc.perform(get(path + "/{runId}", source.projectId(), source.contractId(), runId)
                        .with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.runId").value(runId))
                .andExpect(jsonPath("$.data.inputSnapshotIdentity").value(identity))
                .andExpect(jsonPath("$.data.materials[0].fileId").value(source.fileId()))
                .andExpect(jsonPath("$.data.steps.length()").value(5));
        assertThat(idempotency.size()).isEqualTo(beforeReads);
        mvc.perform(post(path, source.projectId(), source.contractId()).header("Idempotency-Key", key)
                        .contentType("application/json").content(body).with(csrf()).with(authentication(actor)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.runId").value(runId))
                .andExpect(jsonPath("$.data.replayed").value(true));
        // Simulate a later durable retry row only: Phase 2A does not claim or execute it.
        UUID firstEngineStepId = UUID.fromString(JsonPath.read(posted.getResponse().getContentAsString(),
                "$.data.steps[1].stepId"));
        String secondIdentity = ContractSnapshotCanonicalizer.sha256("future-attempt:" + runId + ":2");
        reviewRuns.insert(new ContractReviewStepAttempt(UUID.randomUUID(), UUID.fromString(runId),
                firstEngineStepId, 2, secondIdentity, ContractReviewStepStatus.PENDING,
                null, null, null, ReviewStepExternalCallState.NOT_STARTED, null, null, null,
                null, null, null, null, null, null, 0));
        mvc.perform(get(path + "/{runId}", source.projectId(), source.contractId(), runId)
                        .with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.attempts.length()").value(5));
        mvc.perform(post(path, source.projectId(), source.contractId()).header("Idempotency-Key", key)
                        .contentType("application/json").content(body).with(csrf()).with(authentication(actor)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.runId").value(runId));
        mvc.perform(post(path, source.projectId(), source.contractId()).header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("{\"expectedContractVersion\":0,\"materialFileIds\":[\"%s\"]}"
                                .formatted(UUID.randomUUID()))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isConflict());
        mvc.perform(get(path + "/{runId}", UUID.randomUUID(), source.contractId(), runId)
                        .with(authentication(actor))).andExpect(status().isNotFound());
        mvc.perform(get(path + "/{runId}", source.projectId(), UUID.randomUUID(), runId)
                        .with(authentication(actor))).andExpect(status().isNotFound());
    }

    @Test
    void deterministicCreateReadinessFailureHasEndpointLocalTypedClassification() throws Exception {
        Authentication actor = auth();
        var source = promotedContract(actor);

        mvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs",
                        source.projectId(), source.contractId())
                        .header("Idempotency-Key", "readiness-rejected-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedContractVersion\":1,\"materialFileIds\":[\"%s\"]}"
                                .formatted(source.fileId()))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data.classification").value("READINESS_REJECTED"));

        mvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs",
                        UUID.randomUUID(), UUID.randomUUID())
                        .header("Idempotency-Key", "readiness-not-found-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedContractVersion\":0,\"materialFileIds\":[\"%s\"]}"
                                .formatted(UUID.randomUUID()))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.data.classification").value("READINESS_REJECTED"));
    }

    @Test
    void postNeedsCsrfKeyAndOnlyKnownRolesCanRead() throws Exception {
        Authentication actor = auth();
        mvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs",
                        UUID.randomUUID(), UUID.randomUUID()).header("Idempotency-Key", "csrf-test")
                        .contentType("application/json")
                        .content("{\"expectedContractVersion\":0,\"materialFileIds\":[\"%s\"]}"
                                .formatted(UUID.randomUUID()))
                        .with(authentication(actor))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}",
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                        .with(authentication(unauthorizedRole()))).andExpect(status().isForbidden());
        for (String role : List.of("SYSTEM_ADMIN", "PROCUREMENT_MANAGER", "AUDITOR", "LEGAL")) {
            mvc.perform(get("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}",
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                            .with(authentication(auth(role))))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/steps/{stepId}/execute",
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
                        .header("Idempotency-Key", "unsafe key")
                        .contentType("application/json").content("{}")
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs",
                        UUID.randomUUID(), UUID.randomUUID())
                        .header("Idempotency-Key", "client-claim-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedContractVersion\":0,\"materialFileIds\":[\"%s\"],\"requestedBy\":\"attacker\"}"
                                .formatted(UUID.randomUUID()))
                        .with(csrf()).with(authentication(actor))).andExpect(status().isBadRequest());
        UUID duplicate = UUID.randomUUID();
        mvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs",
                        UUID.randomUUID(), UUID.randomUUID())
                        .header("Idempotency-Key", "duplicate-file-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedContractVersion\":0,\"materialFileIds\":[\"%s\",\"%s\"]}"
                                .formatted(duplicate, duplicate))
                        .with(csrf()).with(authentication(actor))).andExpect(status().isBadRequest());
    }

    @Test
    void concurrentSameKeyRequestsPublishOnlyOneRunAndReplayItsIdentity() throws Exception {
        Authentication actor = auth();
        Source source = promotedContract(actor);
        String key = "run-race-" + UUID.randomUUID();
        String body = "{\"expectedContractVersion\":0,\"materialFileIds\":[\"%s\"]}"
                .formatted(source.fileId());
        int before = idempotency.size();
        var gate = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                gate.await();
                return mvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs",
                        source.projectId(), source.contractId()).header("Idempotency-Key", key)
                        .contentType("application/json").content(body).with(csrf()).with(authentication(actor)))
                        .andReturn().getResponse();
            });
            var second = pool.submit(() -> {
                gate.await();
                return mvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs",
                        source.projectId(), source.contractId()).header("Idempotency-Key", key)
                        .contentType("application/json").content(body).with(csrf()).with(authentication(actor)))
                        .andReturn().getResponse();
            });
            gate.countDown();
            var a = first.get(30, TimeUnit.SECONDS);
            var b = second.get(30, TimeUnit.SECONDS);
            assertThat(a.getStatus()).isEqualTo(202);
            assertThat(b.getStatus()).isEqualTo(202);
            assertThat(JsonPath.<String>read(a.getContentAsString(), "$.data.runId"))
                    .isEqualTo(JsonPath.<String>read(b.getContentAsString(), "$.data.runId"));
            assertThat(idempotency.size()).isEqualTo(before + 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void laterFileAndHumanDecisionChangesCreateNewRunButNeverRewriteOlderSnapshot() throws Exception {
        Authentication actor = auth();
        Source source = promotedContract(actor);
        String path = "/api/v1/projects/{projectId}/contracts/{contractId}/review-runs";
        String oldBody = "{\"expectedContractVersion\":0,\"materialFileIds\":[\"%s\"]}"
                .formatted(source.fileId());
        var old = mvc.perform(post(path, source.projectId(), source.contractId())
                        .header("Idempotency-Key", "frozen-old-" + UUID.randomUUID())
                        .contentType("application/json").content(oldBody)
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isAccepted()).andReturn();
        String oldRunId = JsonPath.read(old.getResponse().getContentAsString(), "$.data.runId");
        String oldIdentity = JsonPath.read(old.getResponse().getContentAsString(), "$.data.inputSnapshotIdentity");
        String oldContractSha = JsonPath.read(old.getResponse().getContentAsString(), "$.data.contractSnapshotSha256");
        String secondFileId = JsonPath.read(mvc.perform(multipart("/api/v1/projects/{projectId}/files",
                        source.projectId()).file(new MockMultipartFile("file", "material2.pdf", "application/pdf",
                                pdf(List.of("Award Notice", "Project No: SYN-1", "Project Name: Synthetic Project"))))
                        .header("Idempotency-Key", "frozen-file-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.id");
        String identityAssessment = JsonPath.read(mvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/identity-assessments",
                        source.projectId(), secondFileId)
                        .header("Idempotency-Key", "frozen-identity-a-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.assessmentId");
        String identityDecision = JsonPath.read(mvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/identity-confirmations",
                        source.projectId(), secondFileId)
                        .header("Idempotency-Key", "frozen-identity-c-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"assessmentId\":\"%s\",\"resolution\":\"CONFIRM_SAME_PROJECT\",\"expectedVersion\":0,\"reason\":\"synthetic\"}"
                                .formatted(identityAssessment))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.decisionId");
        String roleAssessment = JsonPath.read(mvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-assessments",
                        source.projectId(), secondFileId)
                        .header("Idempotency-Key", "frozen-role-a-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.assessmentId");
        String roleDecision = JsonPath.read(mvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        source.projectId(), secondFileId)
                        .header("Idempotency-Key", "frozen-role-c-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"assessmentId\":\"%s\",\"selectedRole\":\"AWARD_NOTICE\",\"expectedVersion\":0,\"reason\":\"synthetic\"}"
                                .formatted(roleAssessment))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.decisionId");
        String newBody = "{\"expectedContractVersion\":0,\"materialFileIds\":[\"%s\",\"%s\"]}"
                .formatted(source.fileId(), secondFileId);
        String newKey = "frozen-new-" + UUID.randomUUID();
        var newer = mvc.perform(post(path, source.projectId(), source.contractId())
                        .header("Idempotency-Key", newKey)
                        .contentType("application/json").content(newBody)
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.materials.length()").value(2))
                .andReturn();
        String newRunId = JsonPath.read(newer.getResponse().getContentAsString(), "$.data.runId");
        assertThat(newRunId).isNotEqualTo(oldRunId);
        assertThat(JsonPath.<String>read(newer.getResponse().getContentAsString(),
                "$.data.inputSnapshotIdentity")).isNotEqualTo(oldIdentity);
        assertThat(newer.getResponse().getContentAsString()).contains(identityDecision, roleDecision);
        String revisedRoleAssessment = JsonPath.read(mvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-assessments",
                        source.projectId(), secondFileId)
                        .header("Idempotency-Key", "revised-role-a-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.assessmentId");
        mvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        source.projectId(), secondFileId)
                        .header("Idempotency-Key", "revised-role-c-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"assessmentId\":\"%s\",\"selectedRole\":\"OTHER\",\"expectedVersion\":1,\"reason\":\"synthetic correction\"}"
                                .formatted(revisedRoleAssessment))
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk());
        String revisedIdentityAssessment = JsonPath.read(mvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/identity-assessments",
                        source.projectId(), secondFileId)
                        .header("Idempotency-Key", "revised-identity-a-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.assessmentId");
        mvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/identity-confirmations",
                        source.projectId(), secondFileId)
                        .header("Idempotency-Key", "revised-identity-c-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"assessmentId\":\"%s\",\"resolution\":\"CONFIRM_NOT_SAME_PROJECT\",\"expectedVersion\":1,\"reason\":\"synthetic correction\"}"
                                .formatted(revisedIdentityAssessment))
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk());
        mvc.perform(get(path + "/{runId}", source.projectId(), source.contractId(), oldRunId)
                        .with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.materials.length()").value(1))
                .andExpect(jsonPath("$.data.inputSnapshotIdentity").value(oldIdentity))
                .andExpect(jsonPath("$.data.contractSnapshotSha256").value(oldContractSha));
        mvc.perform(get(path + "/{runId}", source.projectId(), source.contractId(), newRunId)
                        .with(authentication(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.materials.length()").value(2))
                .andExpect(jsonPath("$.data.materials[?(@.fileId=='%s')].roleDecisionId".formatted(secondFileId))
                        .value(roleDecision));
        mvc.perform(post(path, source.projectId(), source.contractId())
                        .header("Idempotency-Key", newKey).contentType("application/json")
                        .content(newBody).with(csrf()).with(authentication(actor)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.runId").value(newRunId))
                .andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.materials.length()").value(2))
                .andExpect(jsonPath("$.data.materials[?(@.fileId=='%s')].roleDecisionId"
                        .formatted(secondFileId)).value(roleDecision));
    }

    @Test
    void changingCurrentContractFixtureAfterCommitDoesNotRewriteFrozenRunOnGet() throws Exception {
        Authentication actor = auth();
        Source source = promotedContract(actor);
        String path = "/api/v1/projects/{projectId}/contracts/{contractId}/review-runs";
        var created = mvc.perform(post(path, source.projectId(), source.contractId())
                        .header("Idempotency-Key", "contract-mutation-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedContractVersion\":0,\"materialFileIds\":[\"%s\"]}"
                                .formatted(source.fileId()))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isAccepted()).andReturn();
        String originalResponse = created.getResponse().getContentAsString();
        String runId = JsonPath.read(originalResponse, "$.data.runId");
        String f02StepId = stepId(originalResponse, "F02_DETERMINISTIC");
        String executed = executeStep(actor, source, runId, f02StepId,
                "contract-mutation-f02-" + UUID.randomUUID())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String taskId = JsonPath.read(executed, "$.data.auditTask.id");
        Contract original = contracts.findById(UUID.fromString(source.contractId())).orElseThrow();
        var changed = new Contract(original.id(), original.contractNo(), "Later Contract Name",
                original.contractType(), original.partyA(), "Later Supplier", original.partyBCreditCode(),
                Money.of(new BigDecimal("999.00")), original.amountInWords(), original.status(),
                original.versionNo() + 1, original.createdAt(), original.updatedAt(),
                original.lineItems(), original.awardNotice());
        Field field = LocalContractStore.class.getDeclaredField("contracts");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<UUID, Contract> rows = (Map<UUID, Contract>) field.get(contractStore);
        synchronized (contractStore) { rows.put(original.id(), changed); }
        try {
            var reopened = mvc.perform(get(path + "/{runId}", source.projectId(), source.contractId(), runId)
                            .with(authentication(actor)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.contractVersion").value(0))
                    .andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<String>read(reopened, "$.data.contractSnapshotCanonicalJson"))
                    .isEqualTo(JsonPath.<String>read(originalResponse, "$.data.contractSnapshotCanonicalJson"));
            assertThat(JsonPath.<String>read(reopened, "$.data.contractSnapshotSha256"))
                    .isEqualTo(JsonPath.<String>read(originalResponse, "$.data.contractSnapshotSha256"));
            assertThat(JsonPath.<String>read(reopened, "$.data.inputSnapshotIdentity"))
                    .isEqualTo(JsonPath.<String>read(originalResponse, "$.data.inputSnapshotIdentity"));
            String historicalTasks = mvc.perform(get("/api/v1/contract-audit-tasks")
                            .param("keyword", original.contractNo())
                            .param("includeHistory", "true")
                            .with(authentication(actor)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<String>read(historicalTasks, "$.data.items[0].taskId"))
                    .isEqualTo(taskId);
            assertThat(new BigDecimal(JsonPath.<Object>read(historicalTasks,
                    "$.data.items[0].contract.contractAmount").toString()))
                    .isEqualByComparingTo(original.contractAmount().amount());
            assertThat(JsonPath.<String>read(historicalTasks, "$.data.items[0].contract.contractName"))
                    .isEqualTo(original.contractName());
            assertThat(JsonPath.<String>read(historicalTasks, "$.data.items[0].contract.partyB"))
                    .isEqualTo(original.partyB());
            String historicalProgress = mvc.perform(get("/api/v1/contracts/{contractId}/audit-tasks/{taskId}/review-progress",
                            source.contractId(), taskId)
                            .with(authentication(actor)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(JsonPath.<Boolean>read(historicalProgress, "$.data.items[1].source.available"))
                    .isFalse();
            assertThat(JsonPath.<String>read(historicalProgress, "$.data.items[1].source.reason"))
                    .isEqualTo("CONTRACT_VERSION_NOT_PROMOTION_SNAPSHOT");
            assertThat(JsonPath.<List<?>>read(historicalProgress, "$.data.items[1].source.sources"))
                    .isEmpty();
        } finally {
            synchronized (contractStore) { rows.put(original.id(), original); }
        }
    }

    private org.springframework.test.web.servlet.ResultActions executeStep(Authentication actor,
            Source source, String runId, String stepId, String key) throws Exception {
        return mvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs/{runId}/steps/{stepId}/execute",
                source.projectId(), source.contractId(), runId, stepId)
                .header("Idempotency-Key", key).contentType("application/json").content("{}")
                .with(csrf()).with(authentication(actor)));
    }

    private String createRun(Authentication actor, Source source) throws Exception {
        return mvc.perform(post("/api/v1/projects/{projectId}/contracts/{contractId}/review-runs",
                        source.projectId(), source.contractId())
                        .header("Idempotency-Key", "latest-run-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedContractVersion\":0,\"materialFileIds\":[\"%s\"]}"
                                .formatted(source.fileId()))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void durableSnapshotDetectsAttemptUpdateWithoutInsertingRows() throws Exception {
        Authentication actor = auth();
        var source = promotedContract(actor);
        UUID runId = UUID.fromString(JsonPath.read(createRun(actor, source), "$.data.runId"));
        ContractReviewStepAttempt attempt = reviewRuns.findAttemptsByRunId(runId).get(0);
        DurableState before = durableState();
        Instant claimedAt = Instant.parse("2026-09-18T00:00:00Z");
        var changed = new ContractReviewStepAttempt(attempt.attemptId(), attempt.runId(), attempt.stepId(),
                attempt.attemptNo(), attempt.operationIdentity(), ContractReviewStepStatus.RUNNING,
                "snapshot-test-worker", claimedAt.plusSeconds(60), claimedAt,
                ReviewStepExternalCallState.REQUESTED, "request-fingerprint", null, null, null,
                claimedAt, null, null, "AUDIT_TASK", UUID.randomUUID(), attempt.versionNo() + 1);
        Map<UUID, ContractReviewStepAttempt> attempts = rows(reviewRuns, "attempts");
        try {
            attempts.put(attempt.attemptId(), changed);
            assertThat(durableState().attempts()).isNotEqualTo(before.attempts());
        } finally {
            attempts.put(attempt.attemptId(), attempt);
        }
        assertThat(durableState()).isEqualTo(before);
    }

    private DurableState durableState() throws Exception {
        return new DurableState(
                snapshot(reviewRuns, "runs", "status", "versionNo", "startedAt", "completedAt",
                        "failedAt", "unknownAt", "safeReasonCode"),
                snapshot(reviewRuns, "steps", "status", "versionNo", "currentAttemptNo",
                        "outputResourceType", "outputResourceId", "startedAt", "completedAt", "safeReasonCode"),
                snapshot(reviewRuns, "attempts", "status", "versionNo", "leaseOwner", "leaseUntil",
                        "claimedAt", "externalCallState", "externalRequestFingerprint", "providerRequestId",
                        "outputResourceType", "outputResourceId", "startedAt", "completedAt", "safeReasonCode"),
                snapshot(reviewResults, "findings", "versionNo", "statusCode", "attentionCode",
                        "riskLevel", "summary", "recommendation"),
                snapshot(reviewResults, "evidence", "versionNo", "validationStatus", "locatorAvailable",
                        "parseResultId", "parseVersion", "excerptSha256", "boundedExcerpt"),
                snapshot(reviewResults, "comparisons", "comparisonKey", "result", "schemaVersion", "values"),
                snapshot(idempotency, "records", "status", "versionNo", "resultResourceType",
                        "resultResourceId", "completedAt"));
    }

    private static Map<Object, List<Object>> snapshot(Object store, String fieldName, String... properties)
            throws Exception {
        Map<Object, List<Object>> snapshot = new LinkedHashMap<>();
        for (var entry : rows(store, fieldName).entrySet()) {
            List<Object> values = new ArrayList<>();
            for (String property : properties) {
                values.add(entry.getValue().getClass().getMethod(property).invoke(entry.getValue()));
            }
            snapshot.put(entry.getKey(), Collections.unmodifiableList(values));
        }
        return Map.copyOf(snapshot);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> rows(Object store, String fieldName) throws Exception {
        Object target = AopTestUtils.getUltimateTargetObject(store);
        Class<?> type = target.getClass();
        while (type != null && !hasField(type, fieldName)) type = type.getSuperclass();
        if (type == null) throw new NoSuchFieldException(fieldName);
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<K, V>) field.get(target);
    }

    private static boolean hasField(Class<?> type, String fieldName) {
        try {
            type.getDeclaredField(fieldName);
            return true;
        } catch (NoSuchFieldException ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static String stepId(String response, String type) {
        List<String> values = JsonPath.read(response,
                "$.data.steps[?(@.stepType=='" + type + "')].stepId");
        assertThat(values).hasSize(1);
        return values.get(0);
    }

    private String addFrozenMaterial(Authentication actor, String projectId, String fileName,
            byte[] content, String selectedRole) throws Exception {
        String fileId = JsonPath.read(mvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(new MockMultipartFile("file", fileName,
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", content))
                        .header("Idempotency-Key", "run-extra-file-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.id");
        String identityAssessment = JsonPath.read(mvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/identity-assessments", projectId, fileId)
                        .header("Idempotency-Key", "run-extra-identity-a-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.assessmentId");
        mvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/identity-confirmations", projectId, fileId)
                        .header("Idempotency-Key", "run-extra-identity-c-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"assessmentId\":\"%s\",\"resolution\":\"CONFIRM_SAME_PROJECT\",\"expectedVersion\":0,\"reason\":\"synthetic\"}"
                                .formatted(identityAssessment))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk());
        String roleAssessment = JsonPath.read(mvc.perform(post(
                        "/api/v1/projects/{projectId}/files/{fileId}/role-assessments", projectId, fileId)
                        .header("Idempotency-Key", "run-extra-role-a-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.assessmentId");
        mvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations", projectId, fileId)
                        .header("Idempotency-Key", "run-extra-role-c-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"assessmentId\":\"%s\",\"selectedRole\":\"%s\",\"expectedVersion\":0,\"reason\":\"synthetic\"}"
                                .formatted(roleAssessment, selectedRole))
                        .with(csrf()).with(authentication(actor)))
                .andExpect(status().isOk());
        return fileId;
    }

    private Source promotedContract(Authentication actor) throws Exception {
        return promotedContract(actor,
                new MockMultipartFile("file", "001.pdf", "application/pdf", pdf()));
    }

    private Source promotedCompleteContract(Authentication actor) throws Exception {
        return promotedContract(actor,
                new MockMultipartFile("file", "001.pdf", "application/pdf", pdf()),
                completeConfirmationPayload());
    }

    private Source promotedCompleteContractWithout(Authentication actor, String missingField) throws Exception {
        ObjectNode payload = (ObjectNode) new ObjectMapper().readTree(completeConfirmationPayload());
        assertThat(payload.remove(missingField)).isNotNull();
        return promotedContract(actor,
                new MockMultipartFile("file", "001.pdf", "application/pdf", pdf()),
                payload.toString());
    }

    private Source promotedContract(Authentication actor, MockMultipartFile material) throws Exception {
        return promotedContract(actor, material,
                "{\"expectedVersion\":0,\"contractNo\":\"RUN-%s\",\"contractName\":\"Synthetic Contract\",\"contractType\":\"GOODS\",\"partyA\":\"Buyer\",\"partyB\":\"Supplier\",\"contractAmount\":100.00,\"lineItems\":[]}"
                        .formatted(UUID.randomUUID()));
    }

    private Source promotedContract(Authentication actor, MockMultipartFile material, String confirmationPayload)
            throws Exception {
        var project = mvc.perform(post("/api/v1/projects").header("Idempotency-Key", "run-project-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk()).andReturn();
        String projectId = JsonPath.read(project.getResponse().getContentAsString(), "$.data.id");
        mvc.perform(patch("/api/v1/projects/{projectId}", projectId).contentType("application/json")
                        .content("{\"expectedVersion\":0,\"name\":\"Synthetic Project\",\"projectNo\":\"SYN-1\"}")
                        .header("Idempotency-Key", "run-metadata-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk());
        var upload = mvc.perform(multipart("/api/v1/projects/{projectId}/files", projectId)
                        .file(material)
                        .header("Idempotency-Key", "run-file-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk()).andReturn();
        String fileId = JsonPath.read(upload.getResponse().getContentAsString(), "$.data.id");
        var identity = mvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/identity-assessments",
                        projectId, fileId).header("Idempotency-Key", "run-identity-a-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk()).andReturn();
        String assessmentId = JsonPath.read(identity.getResponse().getContentAsString(), "$.data.assessmentId");
        mvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/identity-confirmations", projectId, fileId)
                        .header("Idempotency-Key", "run-identity-c-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"assessmentId\":\"%s\",\"resolution\":\"CONFIRM_SAME_PROJECT\",\"expectedVersion\":0,\"reason\":\"synthetic\"}"
                                .formatted(assessmentId))
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk());
        var role = mvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-assessments", projectId, fileId)
                        .header("Idempotency-Key", "run-role-a-" + UUID.randomUUID())
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk()).andReturn();
        String roleAssessmentId = JsonPath.read(role.getResponse().getContentAsString(), "$.data.assessmentId");
        var roleDecision = mvc.perform(post("/api/v1/projects/{projectId}/files/{fileId}/role-confirmations",
                        projectId, fileId).header("Idempotency-Key", "run-role-c-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"assessmentId\":\"%s\",\"selectedRole\":\"CONTRACT_BODY\",\"expectedVersion\":0,\"reason\":\"synthetic\"}"
                                .formatted(roleAssessmentId))
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk()).andReturn();
        String candidateId = JsonPath.read(roleDecision.getResponse().getContentAsString(), "$.data.candidateId");
        var confirmation = mvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/confirmations",
                        projectId, candidateId).header("Idempotency-Key", "run-candidate-c-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content(confirmationPayload)
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk()).andReturn();
        String confirmationId = JsonPath.read(confirmation.getResponse().getContentAsString(), "$.data.confirmationId");
        var promotion = mvc.perform(post("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/promote",
                        projectId, candidateId).header("Idempotency-Key", "run-promote-" + UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"expectedVersion\":1,\"confirmationId\":\"%s\"}".formatted(confirmationId))
                        .with(csrf()).with(authentication(actor))).andExpect(status().isOk()).andReturn();
        String contractId = JsonPath.read(promotion.getResponse().getContentAsString(), "$.data.contractId");
        return new Source(projectId, fileId, contractId);
    }

    private String completeConfirmationPayload() {
        return """
                {"expectedVersion":0,"contractNo":"RUN-%s","contractName":"Synthetic Contract",
                 "contractType":"GOODS","partyA":"Buyer","partyB":"Supplier",
                 "contractAmount":100.00,"amountInWords":"壹佰元整",
                 "lineItems":[
                   {"itemKey":"ITEM-1","itemName":"First item","quantity":2,
                    "unitPrice":20.00,"declaredAmount":40.00,"sortNo":1},
                   {"itemKey":"ITEM-2","itemName":"Second item","quantity":3,
                    "unitPrice":20.00,"declaredAmount":60.00,"sortNo":2}],
                 "awardNotice":{"noticeNo":"AWARD-1","projectName":"Synthetic Project",
                                "winningBidder":"Supplier","winningAmount":100.0,
                                "winningDate":"2026-09-20"}}
                """.formatted(UUID.randomUUID());
    }

    private record Source(String projectId, String fileId, String contractId) { }
    private record DurableState(Map<Object, List<Object>> runs, Map<Object, List<Object>> steps,
            Map<Object, List<Object>> attempts, Map<Object, List<Object>> findings,
            Map<Object, List<Object>> evidence, Map<Object, List<Object>> typedComparisons,
            Map<Object, List<Object>> idempotencyRecords) { }
    private static Authentication auth() {
        return auth("AUDITOR");
    }
    private static Authentication auth(String role) {
        return auth(role, "run-user-" + role.toLowerCase());
    }
    private static Authentication auth(String role, String userId) {
        CurrentUser user = new CurrentUser(userId,
                "run." + role.toLowerCase(), "Run User", Set.of(role));
        return UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    private static String actorId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
    private static Authentication unauthorizedRole() {
        CurrentUser user = new CurrentUser("run-other", "run.other", "Other", Set.of("PROJECT_MANAGER"));
        return UsernamePasswordAuthenticationToken.authenticated(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_PROJECT_MANAGER")));
    }
    private static byte[] pdf() throws Exception {
        return pdf(List.of("Contract No: EXTRACTED-1", "Contract Name: Extracted Contract",
                "Contract Amount: 100.00"));
    }
    private static byte[] pdf(List<String> lines) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            try (PDPageContentStream stream = new PDPageContentStream(doc, doc.getPage(0))) {
                stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12); stream.newLineAtOffset(40, 700);
                for (String line : lines) {
                    stream.showText(line); stream.newLineAtOffset(0, -16);
                }
                stream.endText();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream(); doc.save(output); return output.toByteArray();
        }
    }
    private static byte[] docx(List<String> lines) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (String line : lines) document.createParagraph().createRun().setText(line);
            document.write(output);
            return output.toByteArray();
        }
    }
}
