package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.contract.application.port.ContractRuleConfigRepository;
import com.fuquan.audit.contract.application.project.ProjectRequestMetadata;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import com.fuquan.audit.contract.domain.Money;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import com.fuquan.audit.contract.infra.memory.reviewrun.InMemoryContractReviewRunRepositories;
import com.fuquan.audit.integration.idempotency.memory.InMemoryIdempotencyAdapter;
import com.fuquan.audit.review.domain.RiskLevel;
import com.fuquan.audit.security.domain.CurrentUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ReviewRunCreatePolicyIntegrationTest {
    private static final UUID PROJECT = UUID.fromString("13000000-0000-0000-0000-000000000001");
    private static final UUID CONTRACT = UUID.fromString("13000000-0000-0000-0000-000000000002");
    private static final UUID FILE = UUID.fromString("13000000-0000-0000-0000-000000000003");
    private static final UUID PARSE = UUID.fromString("13000000-0000-0000-0000-000000000004");
    private static final UUID IDENTITY = UUID.fromString("13000000-0000-0000-0000-000000000005");
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");

    @Test
    void newKeysFreezeCurrentPolicyButSuccessfulOldKeyReplaysAfterPolicySourceFails() {
        var configuration = new AtomicReference<>(List.of(config(1)));
        ContractRuleConfigRepository configSource = configuration::get;
        var policies = new ContractReviewExecutionPolicyResolver(configSource, new F02RulePolicyFingerprint());
        var readiness = mock(ReviewRunReadinessResolver.class);
        var canonicalizer = new ContractSnapshotCanonicalizer();
        var contract = new Contract(CONTRACT, "C-1", "合同", "GOODS", "甲方", "乙方", null,
                Money.of(new BigDecimal("100.00")), null, "READY_FOR_REVIEW", 0,
                NOW, NOW, List.of(), null);
        when(readiness.resolve(any(), eq(PROJECT), eq(CONTRACT), eq(0L), eq(List.of(FILE)),
                eq("actor-1"), eq(NOW))).thenAnswer(call -> {
            UUID runId = call.getArgument(0);
            var material = new ContractReviewRunMaterial(UUID.randomUUID(), runId, 1, PROJECT, FILE,
                    "a".repeat(64), PARSE, 1, "b".repeat(64), DocumentRole.CONTRACT_BODY,
                    null, IDENTITY, null, "actor-1", NOW);
            return new ReviewRunReadinessResolver.Ready(contract, null, null,
                    canonicalizer.canonicalize(contract), List.of(material));
        });
        var stored = new InMemoryContractReviewRunRepositories();
        var idempotency = new InMemoryIdempotencyAdapter();
        var coordinator = new ContractReviewRunCreateTransactionCoordinator(readiness,
                new ReviewRunSnapshotIdentityFactory(), new ReviewRunManifestFactory(),
                stored, stored, stored, stored, idempotency, mock(OperationAuditPort.class),
                Clock.fixed(NOW, ZoneOffset.UTC), policies);
        var actor = new CurrentUser("actor-1", "reviewer", "Reviewer", Set.of("AUDITOR"));
        var first = coordinator.create(command("K1"), actor);
        assertThat(first.run().ruleVersion()).startsWith("f02-policy-v2:");
        assertThat(first.steps().stream().filter(step -> step.stepType() == ContractReviewStepType.F02_DETERMINISTIC)
                .findFirst().orElseThrow().stepPolicyVersion()).isEqualTo(first.run().ruleVersion());
        configuration.set(List.of(config(2)));
        var second = coordinator.create(command("K2"), actor);
        assertThat(second.run().ruleVersion()).isNotEqualTo(first.run().ruleVersion());
        assertThat(second.run().inputSnapshotIdentity()).isNotEqualTo(first.run().inputSnapshotIdentity());
        configuration.set(List.of()); // The current policy is now unavailable; K1 is already durable.
        var replay = coordinator.create(command("K1"), actor);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.run().runId()).isEqualTo(first.run().runId());
        assertThat(idempotency.size()).isEqualTo(2);
    }

    private static CreateContractReviewRunCommand command(String key) {
        return new CreateContractReviewRunCommand(PROJECT, CONTRACT, 0, List.of(FILE), key,
                new ProjectRequestMetadata("request-1", "trace-1", "127.0.0.1"));
    }

    private static ContractRuleConfiguration config(long version) {
        return new ContractRuleConfiguration("F02_A", "F02_A", true, RiskLevel.RED, true,
                version, null, Instant.EPOCH);
    }
}
