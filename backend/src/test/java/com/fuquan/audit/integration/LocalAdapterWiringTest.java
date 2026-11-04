package com.fuquan.audit.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.common.application.port.CachePort;
import com.fuquan.audit.common.application.port.IdempotencyPort;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisPort;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisPort.BudgetSemanticAnalysisItem;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisPort.BudgetSemanticAnalysisRequest;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.ContractTextAnalysisMaterial;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.ContractTextAnalysisRequest;
import com.fuquan.audit.contract.application.port.ContractRepository;
import com.fuquan.audit.contract.application.port.ContractRuleConfigRepository;
import com.fuquan.audit.contract.infra.memory.InMemoryContractRepository;
import com.fuquan.audit.contract.infra.memory.InMemoryContractRuleConfigRepository;
import com.fuquan.audit.integration.audit.memory.InMemoryOperationAuditAdapter;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.review.application.port.AuditCheckResultRepository;
import com.fuquan.audit.review.application.port.AuditTaskRepository;
import com.fuquan.audit.review.application.port.OcrPort;
import com.fuquan.audit.review.application.port.OcrRequest;
import com.fuquan.audit.review.application.port.OcrTestSources;
import com.fuquan.audit.review.domain.AuditTask;
import com.fuquan.audit.review.domain.AuditTaskStatus;
import com.fuquan.audit.review.infra.memory.InMemoryAuditCheckResultRepository;
import com.fuquan.audit.security.application.AuthContext;
import com.fuquan.audit.security.application.port.UserAccountRepository;
import com.fuquan.audit.security.application.port.UserIdentityQueryPort;
import com.fuquan.audit.security.infra.memory.InMemoryUserAccountRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "audit.security.local-account.username=local-auditor",
        "audit.security.local-account.password-hash={noop}local-test-hash",
        "audit.security.local-account.real-name=Local Audit User"
})
@ActiveProfiles("local")
class LocalAdapterWiringTest {

    @Autowired
    private AuditTaskRepository repository;

    @Autowired
    private CachePort cache;

    @Autowired
    private AuthContext authContext;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private UserIdentityQueryPort userIdentityQueryPort;

    @Autowired
    private OperationAuditPort operationAuditPort;

    @Autowired
    private IdempotencyPort idempotencyPort;

    @Autowired
    private ContractRepository contractRepository;

    @Autowired
    private ContractRuleConfigRepository contractRuleConfigRepository;

    @Autowired
    private AuditCheckResultRepository auditCheckResultRepository;

    @Autowired
    private OcrPort ocrPort;

    @Autowired
    private ContractTextAnalysisPort contractTextAnalysisPort;

    @Autowired
    private BudgetSemanticAnalysisPort budgetSemanticAnalysisPort;

    @Test
    void localProfileUsesInMemoryAndFakeAdaptersWithoutExternalServices() {
        UUID taskId = UUID.randomUUID();
        repository.create(AuditTask.pending(
                taskId,
                "CONTRACT",
                UUID.randomUUID(),
                1,
                "F02_DETERMINISTIC",
                "local-user",
                "local-wiring-request",
                "0123456789abcdef0123456789abcdef",
                Instant.now()));
        cache.put("task:" + taskId, "PENDING", Duration.ofMinutes(1));

        assertThat(repository.findById(taskId)).isPresent();
        assertThat(cache.get("task:" + taskId)).contains("PENDING");
        assertThat(authContext.currentUser()).isEmpty();
        assertThat(userAccountRepository).isInstanceOf(InMemoryUserAccountRepository.class);
        assertThat(userIdentityQueryPort).isSameAs(userAccountRepository);
        assertThat(userIdentityQueryPort.findDisplayNameByUserId("00000000-0000-0000-0000-000000000001"))
                .contains("Local Audit User");
        assertThat(operationAuditPort).isInstanceOf(InMemoryOperationAuditAdapter.class);
        assertThat(idempotencyPort).isInstanceOf(InMemoryIdempotencyAdapter.class);
        assertThat(contractRepository).isInstanceOf(InMemoryContractRepository.class);
        assertThat(contractRuleConfigRepository).isInstanceOf(InMemoryContractRuleConfigRepository.class);
        assertThat(auditCheckResultRepository).isInstanceOf(InMemoryAuditCheckResultRepository.class);
        OcrRequest ocrRequest = new OcrRequest(
                "document-local", "document.txt", "text/plain", OcrTestSources.utf8("local"));
        assertThat(ocrPort.recognize(ocrRequest).simulated()).isTrue();
        UUID projectId = UUID.randomUUID();
        var contractAnalysis = contractTextAnalysisPort.analyzeContractText(
                new ContractTextAnalysisRequest(projectId, List.of(
                        new ContractTextAnalysisMaterial(UUID.randomUUID(), "CONTRACT_BODY", "text"))));
        var budgetAnalysis = budgetSemanticAnalysisPort.analyzeBudgetSemantics(
                new BudgetSemanticAnalysisRequest(UUID.randomUUID(), List.of(
                        new BudgetSemanticAnalysisItem(UUID.randomUUID(), "description", null))));
        assertThat(contractAnalysis.available()).isFalse();
        assertThat(contractAnalysis.summaries()).isEmpty();
        assertThat(contractAnalysis.hints()).isEmpty();
        assertThat(contractAnalysis.model()).isEqualTo("UNAVAILABLE_LOCAL_FAKE");
        assertThat(budgetAnalysis.available()).isFalse();
        assertThat(budgetAnalysis.items()).isEmpty();
        assertThat(budgetAnalysis.model()).isEqualTo("UNAVAILABLE_LOCAL_FAKE");
    }
}
