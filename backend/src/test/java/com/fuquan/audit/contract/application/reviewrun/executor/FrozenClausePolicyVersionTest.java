package com.fuquan.audit.contract.application.reviewrun.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.application.clause.BreachClauseAnalyzer;
import com.fuquan.audit.contract.application.clause.ContractTermClauseAnalyzer;
import com.fuquan.audit.contract.application.reviewrun.ContractReviewExecutionPolicyResolver.ResolvedPolicy;
import com.fuquan.audit.contract.application.reviewrun.FrozenEvidenceGrounder;
import com.fuquan.audit.contract.application.reviewrun.ReviewRunManifestFactory;
import com.fuquan.audit.contract.application.reviewrun.ReviewStepExecutor;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FrozenClausePolicyVersionTest {
    @Test
    void newFrozenManifestBindsTheChangedAnalyzersToV5Identities() {
        var manifest = new ReviewRunManifestFactory().create(new UUID(0, 501),
                FrozenClauseExecutorTestSupport.NOW, new ResolvedPolicy("profile", "rules", "model", List.of()));
        assertThat(manifest.steps()).filteredOn(s -> s.stepType() == ContractReviewStepType.BREACH)
                .singleElement().satisfies(s -> assertThat(s.stepPolicyVersion()).isEqualTo("breach-analyzer-v5"));
        assertThat(manifest.steps()).filteredOn(s -> s.stepType() == ContractReviewStepType.TERM)
                .singleElement().satisfies(s -> assertThat(s.stepPolicyVersion()).isEqualTo("term-analyzer-v5"));
    }
    @ParameterizedTest
    @EnumSource(value = ContractReviewStepType.class, names = {"BREACH", "TERM"})
    void v4FrozenPolicyCannotSilentlyExecuteTheNewDocumentChecks(ContractReviewStepType type) {
        String oldPolicy=type==ContractReviewStepType.BREACH?"breach-analyzer-v4":"term-analyzer-v4";
        var execution=FrozenClauseExecutorTestSupport.execution(type,new BigDecimal("10000"),List.of(),oldPolicy);
        var mapper=new FrozenClauseResultMapper(new FrozenEvidenceGrounder(),FrozenClauseExecutorTestSupport.CLOCK);
        ReviewStepExecutor executor=type==ContractReviewStepType.BREACH
                ?new FrozenBreachReviewStepExecutor(new BreachClauseAnalyzer(),new FrozenClauseMaterialAdapter(),mapper)
                :new FrozenTermReviewStepExecutor(new ContractTermClauseAnalyzer(),new FrozenClauseMaterialAdapter(),mapper);
        assertThatThrownBy(()->executor.execute(null,execution.context())).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ContractReviewStepType.class, names = {"BREACH", "TERM"})
    void v3FrozenPolicyCannotSilentlyExecuteTheNewDocumentChecks(ContractReviewStepType type) {
        String oldPolicy=type==ContractReviewStepType.BREACH?"breach-analyzer-v3":"term-analyzer-v3";
        var execution=FrozenClauseExecutorTestSupport.execution(type,new BigDecimal("10000"),List.of(),oldPolicy);
        var mapper=new FrozenClauseResultMapper(new FrozenEvidenceGrounder(),FrozenClauseExecutorTestSupport.CLOCK);
        ReviewStepExecutor executor=type==ContractReviewStepType.BREACH
                ?new FrozenBreachReviewStepExecutor(new BreachClauseAnalyzer(),new FrozenClauseMaterialAdapter(),mapper)
                :new FrozenTermReviewStepExecutor(new ContractTermClauseAnalyzer(),new FrozenClauseMaterialAdapter(),mapper);
        assertThatThrownBy(()->executor.execute(null,execution.context())).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ContractReviewStepType.class, names = {"BREACH", "TERM"})
    void oldV2PolicyIsRejectedBeforeOpeningInputWithChangedQualitySemantics(ContractReviewStepType type) {
        String oldPolicy = type == ContractReviewStepType.BREACH ? "breach-analyzer-v2" : "term-analyzer-v2";
        var execution = FrozenClauseExecutorTestSupport.execution(type, new BigDecimal("10000"), List.of(), oldPolicy);
        var mapper = new FrozenClauseResultMapper(new FrozenEvidenceGrounder(), FrozenClauseExecutorTestSupport.CLOCK);
        ReviewStepExecutor executor = type == ContractReviewStepType.BREACH
                ? new FrozenBreachReviewStepExecutor(new BreachClauseAnalyzer(), new FrozenClauseMaterialAdapter(), mapper)
                : new FrozenTermReviewStepExecutor(new ContractTermClauseAnalyzer(), new FrozenClauseMaterialAdapter(), mapper);
        assertThatThrownBy(() -> executor.execute(null, execution.context()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
    }

    @ParameterizedTest
    @EnumSource(value = ContractReviewStepType.class, names = {"BREACH", "TERM"})
    void oldV1PolicyIsRejectedBeforeEvenOpeningTheInput(ContractReviewStepType type) {
        String oldPolicy = type == ContractReviewStepType.BREACH ? "breach-analyzer-v1" : "term-analyzer-v1";
        var execution = FrozenClauseExecutorTestSupport.execution(type, new BigDecimal("10000"), List.of(), oldPolicy);
        var mapper = new FrozenClauseResultMapper(new FrozenEvidenceGrounder(), FrozenClauseExecutorTestSupport.CLOCK);
        ReviewStepExecutor executor = type == ContractReviewStepType.BREACH
                ? new FrozenBreachReviewStepExecutor(new BreachClauseAnalyzer(), new FrozenClauseMaterialAdapter(), mapper)
                : new FrozenTermReviewStepExecutor(new ContractTermClauseAnalyzer(), new FrozenClauseMaterialAdapter(), mapper);
        assertThatThrownBy(() -> executor.execute(null, execution.context()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode()).isEqualTo(BusinessErrorCode.CONFLICT));
    }
}
