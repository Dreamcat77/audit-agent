package com.fuquan.audit.contract.application.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.contract.application.reviewrun.ContractReviewExecutionPolicyResolver;
import com.fuquan.audit.contract.application.reviewrun.ReviewRunManifestFactory;
import com.fuquan.audit.contract.application.reviewrun.ReviewRunStatusAggregator;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStep;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepStatus;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerRuleCapabilityRegistryTest {
    private final CustomerRuleCapabilityRegistry registry = new CustomerRuleCapabilityRegistry();

    @Test
    void currentProfileDescribesExactlyTheExistingRequiredFiveInStableOrder() {
        var profile = registry.currentProfile();
        assertThat(profile.version()).isEqualTo(ContractReviewExecutionPolicyResolver.REVIEW_PROFILE_VERSION);
        assertThat(profile.required()).extracting(Enum::name).containsExactly(
                "MATERIAL_VALIDATION", "F02_DETERMINISTIC", "SEMANTIC_AI", "BREACH", "TERM");
        assertThat(profile.optional()).isEmpty();
        assertThat(ContractReviewStepType.values()).extracting(Enum::name)
                .containsExactlyElementsOf(profile.required().stream().map(Enum::name).toList());
        assertThat(profile.required().stream().map(registry::find).toList())
                .allSatisfy(descriptor -> {
                    assertThat(descriptor.availability()).isEqualTo(CapabilityAvailability.ENABLED);
                    assertThat(descriptor.stepType()).isNotNull();
                });
    }

    @Test
    void sevenExtensionsHaveOnlySafeGenericPresentationAndNoExecutionContract() {
        var slots = registry.descriptors().subList(5, 12);
        assertThat(slots).extracting(value -> value.code().name())
                .containsExactly("F09", "F10", "F11", "F12", "F13", "F14", "F15");
        assertThat(slots).allSatisfy(slot -> {
            assertThat(slot.displayName()).isEqualTo("待配置审核能力 " + slot.code());
            assertThat(slot.availability()).isEqualTo(CapabilityAvailability.NOT_CONFIGURED);
            assertThat(slot.executionKind()).isEqualTo(ReviewCapabilityDescriptor.ExecutionKind.UNCONFIGURED);
            assertThat(slot.stepType()).isNull();
            assertThat(slot.inputs()).isEmpty();
            assertThat(slot.outputSchema()).isNull();
            assertThat(slot.policyRequirement()).isEqualTo(
                    ReviewCapabilityDescriptor.PolicyRequirement.CUSTOMER_APPROVED_VERSION);
        });
    }

    @Test
    void registrationCreatesNoAdditionalStepsOrAttemptsAndCannotBlockCompletion() {
        assertThat(registry.descriptors()).hasSize(12);
        var manifest = new ReviewRunManifestFactory().create(UUID.randomUUID(), Instant.EPOCH);
        assertThat(manifest.steps()).hasSize(5);
        assertThat(manifest.attempts()).hasSize(4);
        var completed = manifest.steps().stream().map(step -> new ContractReviewStep(
                step.stepId(), step.runId(), step.stepType(), step.required(),
                ContractReviewStepStatus.COMPLETED, step.stepPolicyVersion(), step.currentAttemptNo(),
                step.outputResourceType(), step.outputResourceId(), null, Instant.EPOCH, Instant.EPOCH, 0))
                .toList();
        assertThat(new ReviewRunStatusAggregator().status(completed)).isEqualTo(ContractReviewRunStatus.COMPLETED);
    }

    @Test
    void descriptorsAndProfilesDefensivelyCopyListsAndRejectMutation() {
        var source = new ArrayList<>(registry.currentProfile().required());
        var profile = new ReviewProfileDescriptor("synthetic-v1", source, List.of());
        source.clear();
        assertThat(profile.required()).hasSize(5);
        assertThatThrownBy(() -> profile.required().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registry.descriptors().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> registry.descriptors().get(0).inputs().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void profileRejectsDuplicateOverlappingAndUnconfiguredRequiredCodes() {
        var code = ReviewCapabilityDescriptor.Code.F02_DETERMINISTIC;
        assertThatThrownBy(() -> new ReviewProfileDescriptor("v1", List.of(code, code), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewProfileDescriptor("v1", List.of(code), List.of(code)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewProfileDescriptor("v1",
                List.of(ReviewCapabilityDescriptor.Code.F09), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extensionCannotBeEnabledOrAcquireInventedExecutionMetadata() {
        var slot = registry.find(ReviewCapabilityDescriptor.Code.F09);
        assertThatThrownBy(() -> new ReviewCapabilityDescriptor(slot.code(), slot.displayName(),
                slot.executionKind(), slot.inputs(), slot.outputSchema(), slot.policyRequirement(),
                slot.stepType(), CapabilityAvailability.ENABLED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewCapabilityDescriptor(slot.code(), "合同合规审批",
                slot.executionKind(), slot.inputs(), slot.outputSchema(), slot.policyRequirement(),
                slot.stepType(), slot.availability())).isInstanceOf(IllegalArgumentException.class);
    }
}
