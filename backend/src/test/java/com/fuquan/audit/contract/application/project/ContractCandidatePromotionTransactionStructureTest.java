package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.application.port.ContractRepository;
import com.fuquan.audit.contract.application.project.port.ContractCreationPort;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class ContractCandidatePromotionTransactionStructureTest {

    @Test
    void promotionHasOneTransactionalCoordinatorAndNarrowReadWritePorts() throws Exception {
        Method promote = ContractCandidatePromotionTransactionCoordinator.class.getMethod("promote",
                PromoteContractCandidateCommand.class,
                com.fuquan.audit.security.domain.CurrentUser.class,
                ContractCandidatePromotionTransactionCoordinator.FreshPromotion.class);
        assertThat(promote.getAnnotation(Transactional.class).propagation())
                .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
        Method prepare = ContractCandidatePromotionTransactionCoordinator.class.getMethod("prepare",
                PromoteContractCandidateCommand.class, com.fuquan.audit.security.domain.CurrentUser.class);
        assertThat(prepare.getAnnotation(Transactional.class).propagation())
                .isEqualTo(org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED);
        assertThat(ContractCandidatePromotionService.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().equals("promote"))
                .allSatisfy(method -> assertThat(method.getAnnotation(Transactional.class)).isNull());
        assertThat(Arrays.stream(ContractRepository.class.getDeclaredMethods()).map(Method::getName))
                .containsExactly("findById");
        assertThat(Arrays.stream(ContractCreationPort.class.getDeclaredMethods()).map(Method::getName))
                .containsExactly("insertCompleteSnapshot");
    }

    @Test
    void confirmationBoundPromotionNeverReparsesOrCallsExternalAi() {
        assertThat(Arrays.stream(ContractCandidatePromotionService.class.getDeclaredFields())
                .map(Field::getType)).doesNotContain(LegacyProjectFileParseResultMaterializer.class);
        assertThat(Arrays.stream(CandidateSourceAuthorityValidator.class.getDeclaredFields())
                .map(Field::getType)).doesNotContain(LegacyProjectFileParseResultMaterializer.class);
        assertThat(Arrays.stream(ContractCandidatePromotionTransactionCoordinator.class.getDeclaredFields())
                .map(Field::getType)).doesNotContain(LegacyProjectFileParseResultMaterializer.class)
                .contains(ContractCandidateConfirmationPayloadCanonicalizer.class);
    }

    @Test
    void promotionLocksFileBeforeProjectToMatchIdentityAndMaterializationCommands() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/fuquan/audit/contract/application/project/"
                + "ContractCandidatePromotionTransactionCoordinator.java"));
        String command = source.substring(source.indexOf("private ContractCandidate requireProjectAndCandidate"));
        assertThat(command.indexOf("files.findByProjectIdAndIdForUpdate"))
                .isLessThan(command.indexOf("projects.findByIdForUpdate"));
        assertThat(command.indexOf("projects.findByIdForUpdate"))
                .isLessThan(command.indexOf("candidates.findByProjectIdAndIdForUpdate"));
    }
}
