package com.fuquan.audit.contract.application.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.contract.domain.F02RuleCodes;
import com.fuquan.audit.contract.application.project.extraction.ContractCandidateAssembler;
import com.fuquan.audit.contract.domain.project.ContractCandidate;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.net.URI;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.UUID;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.yaml.snakeyaml.Yaml;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.mockito.Mockito;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

/**
 * Architecture gate for P1-B. It intentionally starts RED until the bounded support-authority
 * surfaces exist; it also prevents that work from widening confirmation, promotion, ReviewRun,
 * F02, or the shared error seam.
 */
class CandidateCrossMaterialAuthorityArchitectureGuardTest {
    private static final Path MAIN = Path.of("src/main/java/com/fuquan/audit");
    private static final Path OPEN_API = Path.of("..", "docs", "openapi.yaml");
    private static final Path FRONTEND_PROJECT = Path.of("..", "frontend", "src", "features", "project");
    private static final Path DM8 = Path.of("..", "db", "dm8");
    private static final Path TEST = Path.of("src", "test");

    @Test
    void requiresOneBoundedSupportAuthorityLifecycleAndBothAuthorityModes() {
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(sourceExists("contract/domain/project/CandidateAuthorityMode.java"))
                .as("CandidateAuthorityMode must model the two P1-B authority modes")
                .isTrue();
        softly.assertThat(sourceExists("contract/domain/project/ContractCandidateSupportAuthority.java"))
                .as("ContractCandidateSupportAuthority must be the bounded durable support domain")
                .isTrue();
        softly.assertThat(sourceExists("contract/application/project/port/ContractCandidateSupportAuthorityRepository.java"))
                .as("the bounded support domain requires a Candidate-scoped repository port")
                .isTrue();
        softly.assertThat(sourceExists("contract/application/project/CandidateSupportAuthorityValidator.java"))
                .as("support authority must be resolved by a server-owned validator")
                .isTrue();
        softly.assertThat(sourceExists(
                "contract/application/project/ContractCandidateSupportReplacementTransactionCoordinator.java"))
                .as("support replacement must have one bounded transaction coordinator")
                .isTrue();

        String modes = source("contract/domain/project/CandidateAuthorityMode.java");
        softly.assertThat(containsAll(modes, "PRIMARY_ONLY_COMPAT", "EXPLICIT_SUPPORT_AUTHORITY"))
                .as("CandidateAuthorityMode must expose both P1-B modes")
                .isTrue();
        softly.assertThat(modes).doesNotContain("AWARD_NOTICE_WINS", "PRECEDENCE");

        String controller = source("contract/api/project/ProjectController.java");
        String openApi = source(OPEN_API);
        String route = "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/"
                + "support-authority-replacements";
        softly.assertThat(controller.contains("support-authority-replacements"))
                .as("ProjectController must expose the bounded replacement endpoint")
                .isTrue();
        softly.assertThat(containsAll(openApi, route + ":", "Idempotency-Key"))
                .as("OpenAPI must document the bounded replacement endpoint and its idempotency key")
                .isTrue();
        MethodTree endpoint = javaMethods(controller).stream().filter(method -> method.getModifiers()
                .getAnnotations().stream().anyMatch(annotation -> annotation.getAnnotationType().toString()
                        .equals("PostMapping") && annotation.toString().contains("support-authority-replacements")))
                .findFirst().orElse(null);
        String serviceField = fieldName(controller, "ContractCandidateSupportReplacementService");
        softly.assertThat(endpoint != null && calls(endpoint.getBody()).stream()
                .anyMatch(call -> call.getMethodSelect().toString().startsWith(serviceField + ".")))
                .as("the annotated endpoint must invoke the planned replacement service")
                .isTrue();
        String service = source("contract/application/project/ContractCandidateSupportReplacementService.java");
        String coordinatorField = fieldName(service, "ContractCandidateSupportReplacementTransactionCoordinator");
        softly.assertThat(endpoint != null && calls(endpoint.getBody()).stream()
                .filter(call -> call.getMethodSelect().toString().startsWith(serviceField + "."))
                .anyMatch(call -> reachableMethods(service, callName(call)).stream()
                        .flatMap(method -> calls(method.getBody()).stream())
                        .anyMatch(nested -> nested.getMethodSelect().toString().startsWith(coordinatorField + "."))))
                .as("the invoked service method must reach the bounded coordinator")
                .isTrue();
        String coordinator = source("contract/application/project/ContractCandidateSupportReplacementTransactionCoordinator.java");
        List<MethodTree> invokedService = endpoint == null ? List.of() : calls(endpoint.getBody()).stream()
                .filter(call -> call.getMethodSelect().toString().startsWith(serviceField + "."))
                .flatMap(call -> reachableMethods(service, callName(call)).stream()).toList();
        softly.assertThat(invokedService.stream().flatMap(method -> calls(method.getBody()).stream())
                .filter(call -> call.getMethodSelect().toString().startsWith(coordinatorField + "."))
                .flatMap(call -> reachableMethods(coordinator, callName(call)).stream())
                .anyMatch(CandidateCrossMaterialAuthorityArchitectureGuardTest::throwsSupportException))
                .as("the coordinator entry actually called by the service must throw the locally handled exception")
                .isTrue();
        softly.assertAll();
    }

    @Test
    void keepsSupportRecoveryEndpointLocalAndMachineClassified() throws Exception {
        SoftAssertions softly = new SoftAssertions();
        String exception = source("contract/application/project/ContractCandidateSupportReplacementException.java");
        List<String> classifications = List.of("OUTCOME_UNCERTAIN", "REQUEST_REJECTED",
                "RESULT_SUPERSEDED", "INTEGRITY_CONFLICT");
        softly.assertThat(containsAll(exception, "extends BusinessException", "enum Classification"))
                .as("the support replacement exception must extend BusinessException and own its classification enum")
                .isTrue();
        softly.assertThat(enumMembers(exception, "Classification"))
                .as("the support replacement exception must declare exactly the four approved classifications")
                .containsExactlyInAnyOrderElementsOf(classifications);

        softly.assertThat(documentedClassifications(source(OPEN_API)))
                .as("409 route -> response schema -> data -> classification must resolve to the exact enum")
                .containsExactlyInAnyOrderElementsOf(classifications);
        if (!exception.isEmpty()) assertActualEndpointExceptionTransport(classifications);

        softly.assertThat(sha256(Path.of("src/main/java/com/fuquan/audit/common/exception/GlobalExceptionHandler.java")))
                // Approved diagnostic logging only; response/classification boundaries remain unchanged.
                .isEqualTo("acab86eb2a056464c53fcd62a8cd0262c393ff16ed89dd6859fd0d1761f6d0b0");
        softly.assertThat(sha256(Path.of("src/main/java/com/fuquan/audit/common/exception/BusinessException.java")))
                .isEqualTo("a33f27e0c75cb2ed55a8b92229279d683109f9860175212915de237c0163c184");
        softly.assertThat(sha256(Path.of("..", "frontend", "src", "api", "client.ts")))
                .isEqualTo("fd82434afc7fe25ef296554bf3ac934fb96bdcdbea069706ec3a521d46b6d8c0");

        Path operationPath = FRONTEND_PROJECT.resolve("candidateSupportAuthorityOperation.ts");
        Path flowPath = FRONTEND_PROJECT.resolve("candidateSupportAuthorityFlow.ts");
        boolean recoverySurfacesExist = Files.isRegularFile(operationPath) && Files.isRegularFile(flowPath);
        softly.assertThat(recoverySurfacesExist)
                .as("frontend support recovery must exist in the dedicated operation and flow modules")
                .isTrue();
        if (recoverySurfacesExist) {
            assertFrontendClassificationBoundary(operationPath, flowPath);
        }
        softly.assertAll();
    }

    @Test
    void readsTheCompleteOpenApiSchemaRatherThanStoppingAtItsFirstProperty() {
        String schema = schemaBlock("""
                components:
                  schemas:
                    CandidateSupportReplacementClassification:
                      type: string
                      description: endpoint-local recovery classification
                      enum:
                        - OUTCOME_UNCERTAIN
                        - REQUEST_REJECTED
                        - RESULT_SUPERSEDED
                        - INTEGRITY_CONFLICT
                    AnotherSchema:
                      type: string
                """, "CandidateSupportReplacementClassification");
        assertThat(yamlEnumMembers(schema)).containsExactlyInAnyOrder("OUTCOME_UNCERTAIN", "REQUEST_REJECTED",
                "RESULT_SUPERSEDED", "INTEGRITY_CONFLICT");
    }

    @Test
    void preservesExistingContractsAndRejectsParallelOrBroadenedScope() throws IOException {
        SoftAssertions softly = new SoftAssertions();
        String production = allJavaSource();
        softly.assertThat(production).doesNotContain("CandidateV2", "CrossMaterialCandidateEngine",
                "SupportAuthorityMigrationRunner");

        String payload = source("contract/application/project/ConfirmedContractPayload.java");
        String canonicalizer = source("contract/application/project/ContractCandidateConfirmationPayloadCanonicalizer.java");
        String confirmationSchema = between(source(OPEN_API), "    ConfirmedContractPayload:",
                "    ApiResponsePromotionResponse:");
        softly.assertThat(payload).doesNotContain("supportAuthority", "supportFile");
        softly.assertThat(canonicalizer).doesNotContain("supportAuthority", "supportFile");
        softly.assertThat(confirmationSchema).doesNotContain("supportAuthority", "supportFile");

        String promotion = between(source("contract/api/project/ProjectController.java"),
                "public record PromotionRequest", "public record PromotionLineItemRequest");
        String promotionSchema = between(source(OPEN_API), "    PromotionRequest:",
                "    ApiResponseConfirmationResponse:");
        softly.assertThat(promotion).doesNotContain("supportAuthority", "supportFile");
        softly.assertThat(promotionSchema).doesNotContain("supportAuthority", "supportFile");

        for (String reviewRunType : List.of(
                "contract/application/reviewrun/ReviewRunReadinessResolver.java",
                "contract/domain/reviewrun/ContractReviewRunMaterial.java",
                "contract/application/reviewrun/FrozenReviewInput.java")) {
            softly.assertThat(source(reviewRunType)).doesNotContain("SupportAuthority", "supportAuthority");
        }
        softly.assertThat(F02RuleCodes.ALL).containsExactly("F02_AMOUNT_WORDS_MATCH",
                "F02_LINE_ITEM_ARITHMETIC", "F02_LINE_SUM_MATCH", "F02_AWARD_AMOUNT_MATCH",
                "F02_AWARD_SUPPLIER_MATCH");
        softly.assertThat(source("contract/domain/ContractRuleConfiguration.java").toLowerCase(Locale.ROOT))
                .doesNotContain("tolerance");
        softly.assertThat(production).doesNotContain("SupportAuthorityMigration", "runSupportAuthorityMigration");
        softly.assertAll();
    }

    @Test
    void enforcesExactTwoModeFactAuthorityAndFindingProjection() throws Exception {
        String policy = source("contract/application/project/extraction/ContractCandidateFactPolicy.java");
        String assembler = source("contract/application/project/extraction/ContractCandidateAssembler.java");
        String validator = source("contract/application/project/CandidateSupportAuthorityValidator.java");
        String projection = source("contract/application/FindingSourceProjectionService.java");
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(javaMethods(policy).stream().anyMatch(method -> method.getName().contentEquals("authorizedFacts")))
                .as("a compiled authority policy must exist").isTrue();
        if (!policy.isEmpty()) assertExecutedPolicy();
        List<MethodTree> legacyOverloads = javaMethods(assembler).stream()
                .filter(method -> method.getName().contentEquals("assemble"))
                .filter(method -> method.getParameters().stream().noneMatch(parameter ->
                        parameter.getType().toString().equals("CandidateAuthorityMode"))).toList();
        softly.assertThat(legacyOverloads).hasSizeGreaterThanOrEqualTo(3);
        for (MethodTree legacy : legacyOverloads) {
            softly.assertThat(legacyReturnsPrimaryPolicy(assembler, legacy, new LinkedHashSet<>()))
                    .as("each retained assemble return must reach the mode-aware PRIMARY_ONLY_COMPAT call and its policy result: %s",
                            legacy.getParameters()).isTrue();
        }
        boolean authoritativeSupport = containsAll(validator, "findBySourceFileIdAndParseResultId",
                "DocumentRole.AWARD_NOTICE", "CONFIRM_SAME_PROJECT")
                && (validator.contains("HUMAN_CONFIRMATION")
                || validator.contains("SYSTEM_AUTO_DECISION")
                || validator.contains("isAuthoritativeDecision"));
        softly.assertThat(authoritativeSupport)
                .as("support authority must be current AWARD_NOTICE evidence from exact file/parse tuples and an authoritative decision")
                .isTrue();
        softly.assertThat(validator).doesNotContain("findByProjectId(");

        String primaryProjection = methodBlock(projection, "project");
        String explicitProjection = methodBlock(projection, "projectExplicit");
        softly.assertThat(validatedExplicitProjection(projection))
                .as("explicit-mode return must use this Candidate's validated cohort; compatibility remains in project")
                .isTrue();
        softly.assertThat(containsAll(primaryProjection, "extractions.findByProjectId(association.projectId())",
                "value.candidateId() == null", "file.category() == ProjectFileCategory.AWARD_NOTICE"))
                .as("PRIMARY_ONLY_COMPAT must retain the current legacy unassigned AWARD_NOTICE Finding fallback")
                .isTrue();
        softly.assertThat(containsAll(explicitProjection, "findBySourceFileIdAndParseResultId",
                "AWARD_AMOUNT", "AWARD_SUPPLIER", "AWARD_SUPPLIER_CREDIT_CODE"))
                .as("explicit Finding projection must use exact current associations and only approved award facts")
                .isTrue();
        softly.assertThat(explicitProjection)
                .as("explicit Finding projection must not fall back to project-wide or legacy award sources")
                .doesNotContain("findByProjectId(", "extractions.findByProjectId(");
        MethodTree explicit = javaMethods(projection).stream()
                .filter(method -> method.getName().contentEquals("projectExplicit"))
                .findFirst().orElse(null);
        if (explicit != null) {
            List<MethodTree> graph = returnedMethodGraph(projection, explicit);
            List<MethodInvocationTree> returned = graph.stream().flatMap(method -> returnedCalls(method).stream()).toList();
            softly.assertThat(validatedSupportFeedsSourceRead(explicit) && exactSourceParseRead(returned))
                    .as("returned evidence must read sourceFileId and parseResultId from the same association row").isTrue();
            softly.assertThat(reachableMethods(projection, "projectExplicit").stream()
                    .flatMap(method -> calls(method.getBody()).stream())
                    .noneMatch(call -> callName(call).equals("findByProjectId")
                            || callName(call).equals("project") || callName(call).equals("projectPrimaryOnly")))
                    .as("all reachable explicit helper methods must exclude project-wide and legacy fallback").isTrue();
        }
        MethodTree currentValidation = javaMethods(validator).stream()
                .filter(method -> method.getName().contentEquals("validateCurrent")).findFirst().orElseThrow();
        softly.assertThat(calls(currentValidation).stream().anyMatch(call ->
                call.getMethodSelect().toString().equals("supports.findCurrentByProjectIdAndCandidateId")
                        && compact(call.getArguments().toString()).equals("candidate.projectId(),candidate.id()")))
                .as("validated cohort must be loaded using this Candidate's project and identity").isTrue();
        softly.assertAll();
    }

    @Test
    void keepsExistingContractMigrationsImmutableAndAllowsOnlyApprovedF06NumberedLine() throws IOException {
        SoftAssertions softly = new SoftAssertions();
        migrationFingerprints().forEach((name, expectedHash) -> softly.assertThat(sha256(DM8.resolve(name)))
                .as("existing migration %s must remain unchanged", name)
                .isEqualTo(expectedHash));
        try (Stream<Path> migrations = Files.list(DM8)) {
            softly.assertThat(migrations.map(path -> path.getFileName().toString())
                    .filter(name -> name.matches("V0(?:2[3-9]|3[0-5])__.*\\.sql"))
                    .sorted().toList())
                    .as("the numbered line after the golden history must be the approved F06 integration only")
                    .containsExactly(
                            "V023__f06_procurement_quote_alert.sql",
                            "V024__procurement_project_creation.sql",
                            "V025__procurement_approval_workflow.sql",
                            "V026__procurement_review_report.sql",
                            "V027__procurement_review_report_snapshot.sql",
                            "V028__procurement_approval_assignment_and_thresholds.sql",
                            "V029__procurement_approval_resubmission.sql",
                            "V030__procurement_approval_return_snapshot.sql",
                            "V031__procurement_notification.sql",
                            "V032__operation_audit_details.sql",
                            "V033__procurement_field_confirmation_audit.sql",
                            "V034__procurement_agent_review_snapshot.sql",
                            "V035__f07_procurement_tender_document.sql");
        }

        String supportMigration = source(DM8.resolve("CONTRACT__contract_candidate_support_authority.sql"));
        softly.assertThat(containsAll(supportMigration,
                "-- MIGRATION-NAMESPACE: CONTRACT",
                "-- MIGRATION-ID: contract-candidate-support-authority",
                "-- EXECUTION: CONTROLLED_MANUAL",
                "-- IDEMPOTENCY: NOT_ASSUMED"))
                .as("the new support migration must be a controlled manual CONTRACT migration")
                .isTrue();
        List<Path> supportMigrationConsumers = migrationConsumers();
        softly.assertThat(supportMigrationConsumers.stream()
                .anyMatch(path -> path.getFileName().toString()
                        .equals("ContractCandidateSupportAuthorityDm8SqlStructureTest.java")))
                .as("the manual migration must have a dedicated structure test when it is introduced")
                .isTrue();
        for (Path consumer : supportMigrationConsumers) {
            softly.assertThat(isReadOnlyMigrationConsumer(consumer, readableSource(consumer)))
                    .as("migration reader %s must use only read/assert primitives; aliases and arbitrary runners are forbidden",
                            consumer).isTrue();
        }
        softly.assertAll();
    }

    private static List<MethodTree> javaMethods(String source) {
        return javaMembers(source).stream().filter(MethodTree.class::isInstance).map(MethodTree.class::cast).toList();
    }

    private static List<Tree> javaMembers(String source) {
        return javaClasses(source).stream().flatMap(type -> type.getMembers().stream()).map(Tree.class::cast).toList();
    }

    private static List<ClassTree> javaClasses(String source) {
        if (source.isBlank()) return List.of();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("the repository's JDK 17 compiler is required").isNotNull();
        var file = new SimpleJavaFileObject(URI.create("string:///Boundary.java"),
                javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return source; }
        };
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("-proc:none"), null, List.of(file));
            List<ClassTree> types = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                // Top-level class members only: comments, string literals and nested decoy classes cannot win selection.
                for (Tree declaration : unit.getTypeDecls()) {
                    if (declaration instanceof ClassTree type) {
                        types.add(type);
                    }
                }
            }
            assertThat(diagnostics.getDiagnostics().stream()
                    .filter(item -> item.getKind() == javax.tools.Diagnostic.Kind.ERROR).toList())
                    .as("architecture source must parse").isEmpty();
            return types;
        } catch (IOException failure) { throw new AssertionError(failure); }
    }

    @Test
    void methodContractsRejectCommentsUnusedCallsAndWrongAssociationOrigins() {
        String fixture = """
                class Fixture {
                  // allowedFactTypes() { return Set.of("DECOY"); }
                  Object assemble() { policy.allowedFactTypes(mode, role); return unchanged; }
                  Object derive() { var allowed = policy.allowedFactTypes(mode, role); return filter(facts, allowed); }
                  Object projectExplicitSupportAuthority() {
                    var cohort = supports.findCurrentByProjectIdAndCandidateId(association.projectId(), candidate.id());
                    return cohort.stream().flatMap(row -> extractions.findBySourceFileIdAndParseResultId(
                        association.projectId(), row.sourceFileId(), row.parseResultId()).stream()).toList();
                  }
                }
                """;
        List<MethodTree> methods = javaMethods(fixture);
        assertThat(methods).extracting(method -> method.getName().toString())
                .containsExactly("assemble", "derive", "projectExplicitSupportAuthority");
        assertThat(returnedCalls(methods.get(0))).noneMatch(call -> callName(call).equals("allowedFactTypes"));
        assertThat(returnedCalls(methods.get(1))).anyMatch(call -> callName(call).equals("allowedFactTypes"));
        assertThat(exactAssociationFeedsSourceRead(methods.get(2))).isTrue();
        String wrongCandidate = fixture.replace("candidate.id()", "otherCandidate.id()");
        assertThat(exactAssociationFeedsSourceRead(javaMethods(wrongCandidate).get(2))).isFalse();
        String decoy = fixture.replace("return cohort.stream()", "return unrelated.stream()");
        assertThat(exactAssociationFeedsSourceRead(javaMethods(decoy).get(2))).isFalse();
        assertThat(exactSourceParseRead(returnedCalls(javaMethods(fixture.replace("row.parseResultId()", "other.parseResultId()")).get(2))))
                .isFalse();
        String dispatcher = "class Dispatch { Object dispatch(Mode mode) { return switch(mode) {"
                + "case PRIMARY_ONLY_COMPAT -> projectPrimaryOnly();"
                + "case EXPLICIT_SUPPORT_AUTHORITY -> projectExplicitSupportAuthority(); }; } }";
        assertThat(returnedModeBranch(javaMethods(dispatcher).get(0), "PRIMARY_ONLY_COMPAT", "projectPrimaryOnly")).isTrue();
        assertThat(returnedModeBranch(javaMethods(dispatcher.replace("projectPrimaryOnly()", "wrong()")).get(0),
                "PRIMARY_ONLY_COMPAT", "projectPrimaryOnly")).isFalse();
    }

    @Test
    void legacyDelegationRejectsDiscardedPrimaryCallsAndDisconnectedPolicyResults() {
        String fixture = """
                class Assembler {
                  ContractCandidateFactPolicy policy;
                  Object assemble() { return assemble(facts); }
                  Object assemble(Object facts) { return derive(facts, CandidateAuthorityMode.PRIMARY_ONLY_COMPAT); }
                  Object derive(Object facts, CandidateAuthorityMode mode) {
                    var allowed = policy.allowedFactTypes(mode, DocumentRole.CONTRACT_BODY);
                    return filter(facts, allowed);
                  }
                }
                """;
        for (MethodTree legacy : javaMethods(fixture).stream()
                .filter(method -> method.getName().contentEquals("assemble")).toList()) {
            assertThat(legacyReturnsPrimaryPolicy(fixture, legacy, new LinkedHashSet<>())).isTrue();
        }
        for (String broken : List.of(
                fixture.replace("return derive(facts, CandidateAuthorityMode.PRIMARY_ONLY_COMPAT);",
                        "derive(facts, CandidateAuthorityMode.PRIMARY_ONLY_COMPAT);"
                                + " return derive(facts, CandidateAuthorityMode.EXPLICIT_SUPPORT_AUTHORITY);"),
                fixture.replace("return filter(facts, allowed);", "return facts;"),
                fixture.replace("return filter(facts, allowed);", "if (skip) return facts; return filter(facts, allowed);"),
                fixture.replace("return assemble(facts);", "if (skip) return facts; return assemble(facts);"),
                fixture.replace("return derive(facts, CandidateAuthorityMode.PRIMARY_ONLY_COMPAT);",
                        "if (skip) return derive(facts, CandidateAuthorityMode.EXPLICIT_SUPPORT_AUTHORITY);"
                                + " return derive(facts, CandidateAuthorityMode.PRIMARY_ONLY_COMPAT);"))) {
            assertThat(legacyReturnsPrimaryPolicy(broken, javaMethods(broken).get(0), new LinkedHashSet<>()))
                    .as("all legacy returned expressions must use primary mode and the connected policy result: %s", broken)
                    .isFalse();
        }
    }

    @Test
    void executedPolicyProbeRejectsExplicitOnlyCompatibility() throws Exception {
        assertExecutedPolicy(PolicyFixture.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertExecutedPolicy(BrokenPrimaryPolicyFixture.class))
                .isInstanceOf(AssertionError.class).hasMessageContaining("PRIMARY_ONLY_COMPAT");
    }

    @Test
    void policyProbeExecutesTheActualFactFilteringBoundary() throws Exception {
        assertExecutedPolicy(com.fuquan.audit.contract.application.project.extraction.ContractCandidateFactPolicy.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertExecutedPolicy(BrokenFactFilteringFixture.class))
                .isInstanceOf(AssertionError.class).hasMessageContaining("PRIMARY_ONLY_COMPAT");
    }

    private static class BrokenFactFilteringFixture {
        List<ExtractionResult> authorizedFacts(ProjectFile primary, List<ExtractionResult> facts,
                List<com.fuquan.audit.contract.application.project.extraction.ContractCandidateFactPolicy.FactSource> sources,
                com.fuquan.audit.contract.domain.project.CandidateAuthorityMode mode) {
            return new com.fuquan.audit.contract.application.project.extraction.ContractCandidateFactPolicy()
                    .authorizedFacts(primary, facts, sources,
                            com.fuquan.audit.contract.domain.project.CandidateAuthorityMode.EXPLICIT_SUPPORT_AUTHORITY);
        }
    }

    @Test
    void legacyPolicySliceRecognizesConstructedFactPolicyAndRejectsDiscardedFacts() {
        String fixture = """
                class Assembler {
                  Object assemble(Object facts) { return derive(facts, CandidateAuthorityMode.PRIMARY_ONLY_COMPAT); }
                  Object derive(Object facts, CandidateAuthorityMode mode) {
                    var allowed = new ContractCandidateFactPolicy().authorizedFacts(primary, facts, supports, mode);
                    return aggregate(allowed);
                  }
                }
                """;
        assertThat(legacyReturnsPrimaryPolicy(fixture, javaMethods(fixture).get(0), new LinkedHashSet<>())).isTrue();
        String discarded = fixture.replace("aggregate(allowed)", "aggregate(facts)");
        assertThat(legacyReturnsPrimaryPolicy(discarded, javaMethods(discarded).get(0), new LinkedHashSet<>())).isFalse();
    }

    @Test
    void migrationReaderAcceptsSafeReadAssertionsAndUnrelatedBehaviorWithoutAllowingExecution() {
        Path test = TEST.resolve("java/MixedTest.java");
        String fixture = """
                import static org.assertj.core.api.Assertions.assertThat;
                import static org.mockito.Mockito.mock;
                import java.nio.file.Files;
                import java.nio.file.Path;
                class MixedTest {
                  void unrelatedBehavior() { var repository = mock(Repository.class); assertThat(repository.find()).isTrue(); }
                  void migrationCheck() {
                    var path = Path.of("CONTRACT__contract_candidate_support_authority.sql");
                    assertThat(Files.exists(path)).isTrue();
                    assertThat(Files.readAllLines(path).subList(0, 6)).containsExactly("CONTROLLED_MANUAL");
                    assertThat(Files.readString(path).indexOf("CONTROLLED_MANUAL")).isGreaterThan(-1);
                  }
                }
                """;
        assertThat(isReadOnlyMigrationConsumer(test, fixture)).isTrue();
        for (String execute : List.of("runner.accept(Files.readString(path));", "statement.execute(sql);",
                "invoke();", "var executor = statement::execute;")) {
            assertThat(isReadOnlyMigrationConsumer(test, fixture.replace("var path =", execute + " var path =")))
                    .as("migration-connected execution remains forbidden: %s", execute).isFalse();
        }
        assertThat(isReadOnlyMigrationConsumer(test, fixture.replace("class MixedTest {",
                "class MixedTest { static { runner.run(); }"))).isFalse();
    }

    @Test
    void migrationReaderRejectsExecutionThroughQualifiedCallersAndMethodReferences() {
        String reader = "class Reader { String sql() { return Files.readString(Path.of("
                + "\"CONTRACT__contract_candidate_support_authority.sql\")); } %s }";
        for (String caller : List.of("void run() { runner.accept(this.sql()); }",
                "void run() { runner.accept(Reader.sql()); }",
                "void run() { var read = this::sql; runner.accept(read); }")) {
            assertThat(isReadOnlyMigrationConsumer(TEST.resolve("java/Reader.java"), reader.formatted(caller)))
                    .as("a caller consuming migration content must be checked: %s", caller).isFalse();
        }
    }

    @Test
    void migrationReaderRejectsClassQualifiedOutgoingHelpersBeforePrimitiveAllowlisting() {
        String fixture = """
                import java.sql.DriverManager;
                class Reader {
                  void check() {
                    var sql = Files.readString(Path.of("CONTRACT__contract_candidate_support_authority.sql"));
                    Reader.of(sql);
                    assertThat(sql).contains("CONTROLLED_MANUAL");
                  }
                  static void of(String sql) { DriverManager.getConnection("jdbc:probe").createStatement().execute(sql); }
                }
                """;
        Path path = TEST.resolve("java/Reader.java");
        assertThat(isReadOnlyMigrationConsumer(path, fixture))
                .as("class-qualified local of must not inherit Path.of's primitive permission").isFalse();
        assertThat(isReadOnlyMigrationConsumer(path, fixture.replace("Reader.of(sql);", "of(sql);"))).isFalse();
        assertThat(isReadOnlyMigrationConsumer(path, fixture.replace("Reader.of(sql);", "this.of(sql);"))).isFalse();
        assertThat(isReadOnlyMigrationConsumer(path, fixture.replace("Reader.of(sql);", "example.Reader.of(sql);")))
                .as("a fully qualified local class must also resolve its callee").isFalse();
        assertThat(isReadOnlyMigrationConsumer(path, fixture.replace("Reader.of(sql);", "Reader alias = this; alias.of(sql);")))
                .as("an explicitly typed local receiver must not hide its callee").isFalse();
        assertThat(isReadOnlyMigrationConsumer(path, fixture.replace("Reader.of(sql);", "")))
                .as("Path.of must not connect an unrelated same-name local helper").isTrue();
    }

    @Test
    void exactSourceReadAcceptsTheSameCandidateOwnerAcrossValidatedHelperBoundaries() {
        String fixture = "class Projection { Object explicit() { return extractions.findBySourceFileIdAndParseResultId("
                + "candidate.projectId(), source.sourceFileId(), source.parseResultId()); } }";
        assertThat(exactSourceParseRead(returnedCalls(javaMethods(fixture).get(0)))).isTrue();
        assertThat(exactSourceParseRead(returnedCalls(javaMethods(fixture.replace("candidate.projectId()",
                "otherCandidate.projectId()")).get(0)))).isFalse();
        assertThat(exactSourceParseRead(returnedCalls(javaMethods(fixture.replace("source.parseResultId()",
                "unrelated.parseResultId()")).get(0)))).isFalse();
    }

    @Test
    void findingGuardRejectsWrongModeCandidateDiscardedValidationAndUnrelatedCohorts() {
        String source = source("contract/application/FindingSourceProjectionService.java");
        assertThat(validatedExplicitProjection(source)).isTrue();
        for (String broken : List.of(
                source.replace("supportAuthority.validateCurrent(candidate)", "supportAuthority.validateCurrent(other)"),
                source.replace("CandidateAuthorityMode.EXPLICIT_SUPPORT_AUTHORITY", "CandidateAuthorityMode.PRIMARY_ONLY_COMPAT"),
                source.replace("projectExplicit(candidate, plan, current)", "projectExplicit(candidate, plan, unrelated)"),
                source.replace("var current = supportAuthority.validateCurrent(candidate);",
                        "supportAuthority.validateCurrent(candidate); var current = unrelated;"))) {
            assertThat(validatedExplicitProjection(broken)).isFalse();
        }
        MethodTree explicit = javaMethods(source).stream().filter(method -> method.getName().contentEquals("projectExplicit"))
                .findFirst().orElseThrow();
        assertThat(validatedSupportFeedsSourceRead(explicit)).isTrue();
        String disconnected = source.replace("current.supports()", "unrelated.supports()");
        MethodTree wrongCohort = javaMethods(disconnected).stream()
                .filter(method -> method.getName().contentEquals("projectExplicit")).findFirst().orElseThrow();
        assertThat(validatedSupportFeedsSourceRead(wrongCohort)).isFalse();
    }

    @Test
    void findingGuardRejectsDisabledOrBypassedExplicitDispatch() {
        String source = source("contract/application/FindingSourceProjectionService.java");
        Matcher dispatch = Pattern.compile("if \\(current\\.authorityMode\\(\\) == CandidateAuthorityMode\\.EXPLICIT_SUPPORT_AUTHORITY\\) \\{"
                + "\\s*return projectExplicit\\(candidate, plan, current\\);\\s*}").matcher(source);
        assertThat(dispatch.find()).as("the mutation must replace the actual explicit branch").isTrue();
        String branch = dispatch.group();
        assertThat(validatedExplicitProjection(source.replace(branch, "if (false) { " + branch + " }")))
                .as("an unreachable explicit branch must not permit legacy fallthrough").isFalse();
        assertThat(validatedExplicitProjection(source.replace(branch,
                "if (bypass) return unavailable(\"BYPASSED\"); " + branch)))
                .as("an extra return before the explicit branch must not bypass its result").isFalse();
    }

    private enum CandidateAuthorityMode { PRIMARY_ONLY_COMPAT, EXPLICIT_SUPPORT_AUTHORITY }
    private enum PolicyRole { CONTRACT_BODY, AWARD_NOTICE }

    private static class PolicyFixture {
        Set<String> allowedFactTypes(CandidateAuthorityMode mode, PolicyRole role) {
            if (mode == CandidateAuthorityMode.PRIMARY_ONLY_COMPAT) {
                return role == PolicyRole.CONTRACT_BODY ? legacyFactTypes() : Set.of();
            }
            return role == PolicyRole.CONTRACT_BODY ? explicitPrimaryFactTypes() : supportFactTypes();
        }
    }

    private static class BrokenPrimaryPolicyFixture {
        Set<String> allowedFactTypes(CandidateAuthorityMode mode, PolicyRole role) {
            // Explicit cases pass, but primary award facts would be silently lost.
            return role == PolicyRole.CONTRACT_BODY ? explicitPrimaryFactTypes() : supportFactTypes();
        }
    }

    @Test
    void executesEveryLegacyAssemblerOverloadAgainstTheCurrentFactOutputBoundary() {
        ProjectFile file = com.fuquan.audit.contract.application.project.parse.ProjectDocumentParserTest
                .file("contract.pdf", "application/pdf");
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        ContractCandidateAssembler assembler = new ContractCandidateAssembler(Clock.fixed(now, ZoneOffset.UTC));
        List<ExtractionResult> facts = new ArrayList<>();
        Map<String, String> amounts = Map.of("AMOUNT_NUMERIC", "120.00", "AWARD_AMOUNT", "98.00",
                "LINE_ITEM_QUANTITY", "2", "LINE_ITEM_UNIT_PRICE", "60.00", "LINE_ITEM_DECLARED_AMOUNT", "120.00");
        for (String factType : legacyFactTypes()) {
            String value = amounts.getOrDefault(factType, factType + "-value");
            facts.add(legacyFact(file, factType, value, amounts.containsKey(factType) ? new BigDecimal(value) : null));
        }
        // Recognized amounts compare numerically; unrelated procurement fields must not enter output.
        facts.add(legacyFact(file, "AWARD_AMOUNT", "98.0", new BigDecimal("98.0")));
        for (String rejected : List.of("PURCHASER", "PROCUREMENT_AGENT", "BUDGET_AMOUNT", "CONTROL_PRICE",
                "PACKAGE_SCOPE")) facts.add(legacyFact(file, rejected, "POISON", null));
        UUID candidateId = UUID.randomUUID();
        UUID parseId = UUID.randomUUID();
        UUID roleId = UUID.randomUUID();
        UUID identityId = UUID.randomUUID();
        List<ContractCandidate> candidates = List.of(
                assembler.assemble(file.projectId(), file, facts),
                assembler.assemble(candidateId, file.projectId(), file, parseId, roleId, "a".repeat(64), facts),
                assembler.assemble(candidateId, file.projectId(), file, parseId, roleId, identityId, "a".repeat(64), facts));
        for (ContractCandidate candidate : candidates) {
            assertThat(candidate).extracting(ContractCandidate::contractNo, ContractCandidate::contractName,
                    ContractCandidate::contractType, ContractCandidate::partyA, ContractCandidate::partyB,
                    ContractCandidate::partyBCreditCode, ContractCandidate::amountInWords,
                    ContractCandidate::awardSupplier, ContractCandidate::awardSupplierCreditCode)
                    .containsExactly("CONTRACT_NO-value", "CONTRACT_NAME-value", null, "PARTY_A-value", "PARTY_B-value",
                            "CREDIT_CODE-value", "AMOUNT_UPPERCASE-value", "AWARD_SUPPLIER-value", "AWARD_SUPPLIER_CREDIT_CODE-value");
            assertThat(candidate.contractAmount()).isEqualByComparingTo("120.00");
            assertThat(candidate.awardAmount()).isEqualByComparingTo("98.00");
            assertThat(candidate.lineItems()).singleElement().satisfies(line -> {
                assertThat(line.itemKey()).isEqualTo("LINE_ITEM_KEY-value");
                assertThat(line.itemName()).isEqualTo("LINE_ITEM_NAME-value");
                assertThat(line.quantity()).isEqualByComparingTo("2");
                assertThat(line.unitPrice()).isEqualByComparingTo("60.00");
                assertThat(line.declaredAmount()).isEqualByComparingTo("120.00");
                assertThat(line.sortNo()).isZero();
            });
        }
    }

    private static ExtractionResult legacyFact(ProjectFile file, String type, String value, BigDecimal amount) {
        return new ExtractionResult(UUID.randomUUID(), file.projectId(), file.id(), null,
                String.format("%064x", type.hashCode() & 0xffffffffL), "project-extraction-v1", type,
                type.startsWith("LINE_ITEM_") ? "ITEM-1" : null, value, value, amount, "bounded evidence", 1, null,
                "pdf/page:1/row:1", Instant.parse("2026-08-26T00:00:00Z"));
    }

    @Test
    void migrationContractRejectsRootScriptsConstructedMarkersAndExecutionAliases() {
        String marker = "CONTRACT__contract_candidate_support_authority.sql";
        String reader = "class Reader { void verify() { var sql = Files.readString(Path.of(\"" + marker
                + "\")); assertThat(sql).contains(\"CONTROLLED_MANUAL\"); } }";
        Path test = TEST.resolve("java/StructureTest.java");
        assertThat(migrationReference(reader)).isTrue();
        assertThat(isReadOnlyMigrationConsumer(test, reader)).isTrue();
        assertThat(isReadOnlyMigrationConsumer(Path.of("start-local-dev.bat"), "disql " + marker)).isFalse();
        assertThat(migrationReference("String prefix = \"CONTRACT__\"; String rest = \"contract_candidate_support_authority.sql\";"))
                .isTrue();
        for (String execute : List.of("statement.executeUpdate(sql);", "runner.accept(sql);", "alias.run(sql);",
                "SqlRunner.runScript(sql);", "var alias = statement::execute; alias.accept(sql);", "new Evil(sql);")) {
            assertThat(isReadOnlyMigrationConsumer(test, reader.replace("assertThat(sql)", execute + " assertThat(sql)")))
                    .as("execution alias %s", execute).isFalse();
        }
        assertThat(isReadOnlyMigrationConsumer(test, reader.replace("class Reader {", "class Reader { static { runner.run(); }")))
                .isFalse();
    }

    @Test
    void migrationReferenceDistinguishesAuthorityFingerprintVersionFromMigrationIdentity() {
        assertThat(migrationReference("add(digest, \"contract-candidate-support-authority-v1\", mode);"))
                .as("a versioned authority fingerprint token does not read or execute a migration").isFalse();
        assertThat(migrationReference("runner.accept(\"contract-candidate-support-authority\");"))
                .as("the exact logical migration identity must still be detected").isTrue();
    }

    @Test
    void migrationReferencesRecognizeDdlFormsAndAliasesWithoutMatchingMapperDml() {
        String table = "CONTRACT_CANDIDATE_SUPPORT_AUTHORITY";
        for (String ddl : List.of("CREATE TABLE " + table + " (ID VARCHAR(36))",
                "CREATE TABLE IF NOT EXISTS " + table + " (ID VARCHAR(36))",
                "ALTER TABLE " + table + " ADD EXTRA VARCHAR(36)",
                "DROP TABLE IF EXISTS " + table, "TRUNCATE TABLE " + table)) {
            assertThat(migrationReference(ddl)).as("direct DDL %s", ddl).isTrue();
            String constructed = "String ddl = \"" + ddl.replace(table, "\" + table + \"") + "\";"
                    + " String table = \"CONTRACT_CANDIDATE_\" + \"SUPPORT_AUTHORITY\"; runner.accept(ddl);";
            assertThat(migrationReference(constructed)).as("constructed table/DDL alias %s", constructed).isTrue();
            assertThat(isReadOnlyMigrationConsumer(TEST.resolve("java/Runner.java"), "class Runner { void run() {"
                    + constructed + "} }" )).isFalse();
        }
        assertThat(migrationReference("String table = \"" + table + "\"; execute(\"ALTER TABLE \" + table);"))
                .as("bare table literal with separate DDL command").isTrue();
        assertThat(migrationReference("var sql = \"CREATE \" + \"TABLE IF NOT EXISTS \" + \"" + table + " (ID INT)\";"))
                .isTrue();
        for (String dml : List.of("SELECT * FROM " + table, "UPDATE " + table + " SET CURRENT_FLAG = 0",
                "INSERT INTO " + table + " (ID) VALUES (?)", "DELETE FROM " + table,
                "<select id=\"findCurrent\">SELECT * FROM " + table + "</select>",
                "String table = \"" + table + "\"; jdbc.update(\"UPDATE \" + table + \" SET CURRENT_FLAG = 0\");",
                "CREATE TABLE OTHER_TABLE (ID INT); SELECT * FROM " + table,
                "ALTER TABLE OTHER_TABLE ADD COLUMN ID INT; UPDATE " + table + " SET CURRENT_FLAG = 0")) {
            assertThat(migrationReference(dml)).as("ordinary mapper/DML reference %s", dml).isFalse();
        }
    }

    @Test
    void openApiContractRequiresTheRouteToReferenceTheClassificationEnum() {
        String route = "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/support-authority-replacements";
        Map<String, Object> schemas = new java.util.LinkedHashMap<>();
        schemas.put("Error", Map.of("properties", Map.of("data", Map.of("$ref", "#/components/schemas/Data"))));
        schemas.put("Data", Map.of("properties", Map.of("classification", Map.of("$ref", "#/components/schemas/Classification"))));
        schemas.put("Classification", Map.of("type", "string", "enum", List.of("OUTCOME_UNCERTAIN", "REQUEST_REJECTED",
                "RESULT_SUPERSEDED", "INTEGRITY_CONFLICT")));
        Map<String, Object> document = Map.of("components", Map.of("schemas", schemas), "paths", Map.of(route,
                Map.of("post", Map.of("responses", Map.of("409", Map.of("content", Map.of("application/json",
                        Map.of("schema", Map.of("$ref", "#/components/schemas/Error")))))))));
        assertThat(documentedClassifications(new Yaml().dump(document))).hasSize(4);
        schemas.put("Error", Map.of("properties", Map.of("data", Map.of("type", "object"))));
        assertThat(documentedClassifications(new Yaml().dump(document))).isEmpty();
    }

    @Test
    void executableFrontendBoundaryAcceptsDataOnlyParserAndRejectsMessageParser(
            @org.junit.jupiter.api.io.TempDir Path temporary) throws Exception {
        Path project = Files.createDirectories(temporary.resolve("features/project"));
        Path api = Files.createDirectories(temporary.resolve("api"));
        Files.writeString(api.resolve("client.ts"), """
                export class ApiError extends Error {
                  constructor(status: number, code: string, message: string, data?: unknown) {
                    super(message); this.status = status; this.code = code; this.data = data;
                  }
                }
                """);
        Path operation = project.resolve("candidateSupportAuthorityOperation.ts");
        String parser = """
                import { ApiError } from '../../api/client.ts';
                export function parseCandidateSupportAuthorityClassification(error: unknown) {
                  if (!(error instanceof ApiError)) return null;
                  const data = error.data;
                  if (!data || typeof data !== 'object' || Array.isArray(data)) return null;
                  const value = data.classification;
                  return ['OUTCOME_UNCERTAIN', 'REQUEST_REJECTED', 'RESULT_SUPERSEDED', 'INTEGRITY_CONFLICT']
                    .includes(value) ? value : null;
                }
                """;
        Files.writeString(operation, parser);
        Path flow = project.resolve("candidateSupportAuthorityFlow.ts");
        Files.writeString(flow, """
                import { parseCandidateSupportAuthorityClassification } from './candidateSupportAuthorityOperation.ts';
                export async function replaceCandidateSupportAndReconcile() {
                  try { await post(); } catch (error) {
                    const classification = parseCandidateSupportAuthorityClassification(error);
                    if (classification === 'REQUEST_REJECTED') clear();
                    throw error;
                  }
                }
                """);
        assertFrontendClassificationBoundary(operation, flow);
        Files.writeString(operation, parser.replace("const data = error.data;", "const data = helper(error);" )
                + "\nfunction helper(alias) { return { classification: alias['mes' + 'sage'] }; }");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assertFrontendClassificationBoundary(operation, flow))
                .isInstanceOf(AssertionError.class).hasMessageContaining("parser must not read ANY other error property");
    }

    @Test
    void endpointProbeExecutesTheServiceAndTheExactLocalExceptionHandler() throws Exception {
        List<String> values = List.of("OUTCOME_UNCERTAIN", "REQUEST_REJECTED", "RESULT_SUPERSEDED", "INTEGRITY_CONFLICT");
        assertEndpointExceptionTransport(EndpointFixture.class, ExceptionFixture.class, values);
        EndpointFixture.corruptClassification = true;
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    assertEndpointExceptionTransport(EndpointFixture.class, ExceptionFixture.class, values))
                    .isInstanceOf(AssertionError.class);
        } finally { EndpointFixture.corruptClassification = false; }
    }

    public interface ContractCandidateSupportReplacementService { Object replace(); }

    public static class ExceptionFixture extends com.fuquan.audit.common.exception.BusinessException {
        public enum Classification { OUTCOME_UNCERTAIN, REQUEST_REJECTED, RESULT_SUPERSEDED, INTEGRITY_CONFLICT }
        public ExceptionFixture() { super(BusinessErrorCode.CONFLICT); }
        public Classification classification() { return Classification.OUTCOME_UNCERTAIN; }
    }

    @org.springframework.web.bind.annotation.RestController
    @org.springframework.context.annotation.Profile("architecture-guard-fixture")
    public static class EndpointFixture {
        static boolean corruptClassification;
        private final ContractCandidateSupportReplacementService service;
        public EndpointFixture(ContractCandidateSupportReplacementService service) { this.service = service; }
        @PostMapping("/api/v1/projects/{projectId}/contract-candidates/{candidateId}/support-authority-replacements")
        public Object replace() { return service.replace(); }
        @ExceptionHandler(ExceptionFixture.class)
        public org.springframework.http.ResponseEntity<?> failure(ExceptionFixture exception) {
            return org.springframework.http.ResponseEntity.status(exception.errorCode().httpStatus()).body(
                    com.fuquan.audit.common.api.ApiResponse.failure(409, exception.getMessage(), Map.of("classification",
                            corruptClassification ? "WRONG" : exception.classification().name())));
        }
    }

    private static List<MethodInvocationTree> calls(Tree tree) {
        List<MethodInvocationTree> calls = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitMethodInvocation(MethodInvocationTree node, Void ignored) {
                calls.add(node); return super.visitMethodInvocation(node, ignored);
            }
        }.scan(tree, null);
        return calls;
    }

    private static String callName(MethodInvocationTree call) {
        return call.getMethodSelect() instanceof MemberSelectTree member
                ? member.getIdentifier().toString() : call.getMethodSelect().toString();
    }

    private static List<MethodTree> reachableMethods(String source, String name) {
        List<MethodTree> methods = javaMethods(source);
        List<MethodTree> visited = new ArrayList<>();
        Deque<MethodTree> pending = new ArrayDeque<>(methods.stream()
                .filter(method -> method.getName().contentEquals(name)).toList());
        while (!pending.isEmpty()) {
            MethodTree method = pending.removeFirst();
            if (visited.contains(method)) continue;
            visited.add(method);
            for (MethodInvocationTree call : calls(method.getBody())) {
                if (isLocalCall(call)) methods.stream().filter(target -> target.getName().contentEquals(callName(call))
                        && target.getParameters().size() == call.getArguments().size()).forEach(pending::add);
            }
        }
        return visited;
    }

    private static boolean isLocalCall(MethodInvocationTree call) {
        return call.getMethodSelect() instanceof IdentifierTree
                || call.getMethodSelect().toString().startsWith("this.");
    }

    // A bounded return-value slice: discarded policy/repository calls cannot satisfy the architecture contract.
    // Dedicated later tests own aggregation, staleness, concurrency and full recovery semantics.
    private static List<MethodInvocationTree> returnedCalls(MethodTree method) {
        return returnedCalls(method, null);
    }

    private static List<MethodInvocationTree> returnedCalls(MethodTree method, Tree returnedExpression) {
        List<Tree> roots = new ArrayList<>();
        if (returnedExpression != null) roots.add(returnedExpression);
        Map<String, Tree> definitions = new java.util.LinkedHashMap<>();
        List<MethodInvocationTree> mutations = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitReturn(ReturnTree node, Void ignored) {
                if (returnedExpression == null && node.getExpression() != null) roots.add(node.getExpression());
                return null;
            }
            @Override public Void visitVariable(VariableTree node, Void ignored) {
                if (node.getInitializer() != null) definitions.put(node.getName().toString(), node.getInitializer());
                return super.visitVariable(node, ignored);
            }
            @Override public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void ignored) {
                definitions.put(node.getVariable().getName().toString(), node.getExpression());
                return super.visitEnhancedForLoop(node, ignored);
            }
            @Override public Void visitMethodInvocation(MethodInvocationTree node, Void ignored) {
                if (Set.of("add", "addAll", "put", "putAll").contains(callName(node))) mutations.add(node);
                if (node.getMethodSelect() instanceof MemberSelectTree receiver) {
                    node.getArguments().stream().filter(LambdaExpressionTree.class::isInstance)
                            .map(LambdaExpressionTree.class::cast).forEach(lambda -> lambda.getParameters()
                                    .forEach(parameter -> definitions.put(parameter.getName().toString(), receiver.getExpression())));
                }
                return super.visitMethodInvocation(node, ignored);
            }
        }.scan(method.getBody(), null);
        Set<String> needed = new LinkedHashSet<>();
        List<MethodInvocationTree> result = new ArrayList<>();
        for (int index = 0; index < roots.size(); index++) {
            Tree root = roots.get(index);
            result.addAll(calls(root));
            new TreeScanner<Void, Void>() {
                @Override public Void visitIdentifier(IdentifierTree node, Void ignored) {
                    String id = node.getName().toString();
                    if (needed.add(id)) {
                        if (definitions.containsKey(id)) roots.add(definitions.get(id));
                        mutations.stream().filter(call -> call.getMethodSelect().toString().startsWith(id + "."))
                                .forEach(roots::add);
                    }
                    return null;
                }
            }.scan(root, null);
        }
        return result;
    }

    private static List<ExpressionTree> methodReturnExpressions(MethodTree method) {
        List<ExpressionTree> returns = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitReturn(ReturnTree node, Void ignored) {
                if (node.getExpression() != null) returns.add(node.getExpression());
                return null;
            }
            @Override public Void visitLambdaExpression(LambdaExpressionTree node, Void ignored) { return null; }
            @Override public Void visitClass(ClassTree node, Void ignored) { return null; }
        }.scan(method.getBody(), null);
        return returns;
    }

    private static boolean legacyReturnsPrimaryPolicy(String source, MethodTree method, Set<String> visited) {
        String signature = method.getName() + "/" + method.getParameters();
        if (!visited.add(signature)) return false;
        List<ExpressionTree> returns = methodReturnExpressions(method);
        return !returns.isEmpty() && returns.stream().allMatch(expression -> returnedCalls(method, expression).stream()
                .filter(CandidateCrossMaterialAuthorityArchitectureGuardTest::isLocalCall).anyMatch(call ->
                        javaMethods(source).stream().filter(target -> target.getName().contentEquals(callName(call))
                                && target.getParameters().size() == call.getArguments().size()).anyMatch(target -> {
                            for (int index = 0; index < target.getParameters().size(); index++) {
                                if (target.getParameters().get(index).getType().toString().equals("CandidateAuthorityMode")) {
                                    return call.getArguments().get(index).toString().equals("CandidateAuthorityMode.PRIMARY_ONLY_COMPAT")
                                            && everyReturnUsesPolicy(source, target, new LinkedHashSet<>());
                                }
                            }
                            return legacyReturnsPrimaryPolicy(source, target, new LinkedHashSet<>(visited));
                        })));
    }

    private static boolean everyReturnUsesPolicy(String source, MethodTree method, Set<String> visited) {
        if (!visited.add(method.getName() + "/" + method.getParameters())) return false;
        List<ExpressionTree> returns = methodReturnExpressions(method);
        return !returns.isEmpty() && returns.stream().allMatch(expression -> returnedCalls(method, expression).stream()
                .anyMatch(call -> isFactPolicyCall(source, call)
                        || isLocalCall(call) && javaMethods(source).stream()
                                .filter(target -> target.getName().contentEquals(callName(call))
                                        && target.getParameters().size() == call.getArguments().size())
                                .anyMatch(target -> everyReturnUsesPolicy(source, target, new LinkedHashSet<>(visited)))));
    }

    private static boolean isFactPolicyCall(String source, MethodInvocationTree call) {
        if (!Set.of("allowedFactTypes", "authorizedFacts").contains(callName(call))
                || !(call.getMethodSelect() instanceof MemberSelectTree select)) return false;
        if (select.getExpression() instanceof NewClassTree created) {
            return created.getIdentifier().toString().equals("ContractCandidateFactPolicy");
        }
        String field = fieldName(source, "ContractCandidateFactPolicy");
        return !field.isEmpty() && select.getExpression().toString().equals(field);
    }

    private static List<MethodTree> returnedMethodGraph(String source, MethodTree entry) {
        List<MethodTree> methods = javaMethods(source);
        List<MethodTree> visited = new ArrayList<>();
        Deque<MethodTree> pending = new ArrayDeque<>();
        pending.add(entry);
        Set<String> signatures = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            MethodTree method = pending.removeFirst();
            if (!signatures.add(method.getName() + "/" + method.getParameters())) continue;
            visited.add(method);
            for (MethodInvocationTree call : returnedCalls(method)) {
                if (isLocalCall(call)) methods.stream().filter(target -> target.getName().contentEquals(callName(call))
                        && target.getParameters().size() == call.getArguments().size()).forEach(pending::add);
            }
        }
        return visited;
    }

    private static boolean exactSourceParseRead(List<MethodInvocationTree> calls) {
        return calls.stream().filter(call -> callName(call).equals("findBySourceFileIdAndParseResultId"))
                .anyMatch(call -> {
                    if (call.getArguments().size() != 3) return false;
                    String project = compact(call.getArguments().get(0).toString());
                    String file = compact(call.getArguments().get(1).toString());
                    String parse = compact(call.getArguments().get(2).toString());
                    return Set.of("association.projectId()", "candidate.projectId()").contains(project) && file.endsWith(".sourceFileId()")
                            && parse.equals(file.replace(".sourceFileId()", ".parseResultId()"));
                });
    }

    private static boolean validatedExplicitProjection(String projection) {
        MethodTree entry = javaMethods(projection).stream().filter(method -> method.getName().contentEquals("project"))
                .findFirst().orElse(null);
        if (entry == null || entry.getBody() == null) return false;
        // Follow the bounded, direct control path for a cross-file plan with a present support cohort.
        // Finding a matching branch anywhere below the public method is insufficient: wrappers,
        // intervening returns and finally overrides must not make it unreachable or bypass its result.
        List<? extends StatementTree> top = entry.getBody().getStatements();
        IfTree crossFile = top.stream().filter(IfTree.class::isInstance).map(IfTree.class::cast)
                .filter(branch -> hasCondition(branch, "(plan.crossFile())")).findFirst().orElse(null);
        if (crossFile == null || top.subList(0, top.indexOf(crossFile)).stream()
                .anyMatch(statement -> statement instanceof ReturnTree || statement instanceof ThrowTree
                        || statement instanceof IfTree branch && hasCondition(branch, "(true)"))) return false;
        if (!(crossFile.getThenStatement() instanceof BlockTree crossBody) || crossBody.getStatements().size() != 1
                || !(crossBody.getStatements().get(0) instanceof IfTree supportPresence)
                || !hasCondition(supportPresence, "(supports==null)")
                || !(supportPresence.getElseStatement() instanceof BlockTree present)) return false;
        List<? extends StatementTree> cohort = present.getStatements();
        if (cohort.size() != 3 || !(cohort.get(0) instanceof VariableTree rows)
                || !rows.getName().contentEquals("rows") || rows.getInitializer() == null
                || !compact(rows.getInitializer().toString()).equals(
                        "supports.findCurrentByProjectIdAndCandidateId(candidate.projectId(),candidate.id())")
                || !(cohort.get(1) instanceof IfTree missing) || !hasCondition(missing, "(rows==null)")
                || !(cohort.get(2) instanceof IfTree populated)
                || !hasCondition(populated, "(!rows.isEmpty()||candidate.supportAuthorityVersionNo()!=null)")
                || !(populated.getThenStatement() instanceof BlockTree populatedBody)
                || populatedBody.getStatements().size() != 1
                || !(populatedBody.getStatements().get(0) instanceof TryTree validation)
                || !validation.getResources().isEmpty() || validation.getFinallyBlock() != null) return false;
        List<? extends StatementTree> dispatch = validation.getBlock().getStatements();
        if (dispatch.size() != 2 || !(dispatch.get(0) instanceof VariableTree current)
                || !current.getName().contentEquals("current") || current.getInitializer() == null
                || !compact(current.getInitializer().toString()).equals("supportAuthority.validateCurrent(candidate)")
                || !(dispatch.get(1) instanceof IfTree explicit)
                || !hasCondition(explicit, "(current.authorityMode()==CandidateAuthorityMode.EXPLICIT_SUPPORT_AUTHORITY)")
                || explicit.getElseStatement() != null) return false;
        StatementTree result = explicit.getThenStatement();
        if (result instanceof BlockTree block) {
            if (block.getStatements().size() != 1) return false;
            result = block.getStatements().get(0);
        }
        return result instanceof ReturnTree returned && returned.getExpression() != null
                && compact(returned.getExpression().toString()).equals("projectExplicit(candidate,plan,current)");
    }

    private static boolean hasCondition(IfTree branch, String condition) {
        return compact(branch.getCondition().toString()).equals(condition);
    }

    private static boolean validatedSupportFeedsSourceRead(MethodTree explicit) {
        boolean[] found = {false};
        new TreeScanner<Void, Void>() {
            @Override public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void ignored) {
                String row = node.getVariable().getName().toString();
                boolean currentCohort = calls(node.getExpression()).stream().anyMatch(call ->
                        call.getMethodSelect().toString().equals("current.supports") && call.getArguments().isEmpty());
                found[0] |= currentCohort && calls(node.getStatement()).stream().anyMatch(call ->
                        callName(call).equals("findBySourceFileIdAndParseResultId")
                                && compact(call.getArguments().toString()).equals("candidate.projectId(),"
                                        + row + ".sourceFileId()," + row + ".parseResultId()"));
                return super.visitEnhancedForLoop(node, ignored);
            }
        }.scan(explicit, null);
        return found[0];
    }

    private static boolean returnedModeBranch(MethodTree method, String mode, String target) {
        if (method == null || method.getBody() == null) return false;
        return method.getBody().getStatements().stream().filter(ReturnTree.class::isInstance).map(ReturnTree.class::cast)
                .map(ReturnTree::getExpression).filter(SwitchExpressionTree.class::isInstance).map(SwitchExpressionTree.class::cast)
                .flatMap(branches -> branches.getCases().stream())
                .filter(branch -> branch.getExpressions().stream().anyMatch(label -> label.toString().equals(mode)))
                .anyMatch(branch -> {
                    Tree body = branch.getBody();
                    if (body instanceof YieldTree yield) body = yield.getValue();
                    return body instanceof MethodInvocationTree call && callName(call).equals(target);
                });
    }

    private static boolean exactAssociationFeedsSourceRead(MethodTree method) {
        Map<String, Tree> definitions = new java.util.LinkedHashMap<>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitVariable(VariableTree node, Void ignored) {
                if (node.getInitializer() != null) definitions.put(node.getName().toString(), node.getInitializer());
                return super.visitVariable(node, ignored);
            }
            @Override public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void ignored) {
                definitions.put(node.getVariable().getName().toString(), node.getExpression());
                return super.visitEnhancedForLoop(node, ignored);
            }
            @Override public Void visitMethodInvocation(MethodInvocationTree node, Void ignored) {
                if (node.getMethodSelect() instanceof MemberSelectTree member) {
                    node.getArguments().stream().filter(LambdaExpressionTree.class::isInstance)
                            .map(LambdaExpressionTree.class::cast).forEach(lambda -> lambda.getParameters()
                                    .forEach(parameter -> definitions.put(parameter.getName().toString(), member.getExpression())));
                }
                return super.visitMethodInvocation(node, ignored);
            }
        }.scan(method.getBody(), null);
        return returnedCalls(method).stream().filter(call -> callName(call).equals("findBySourceFileIdAndParseResultId"))
                .filter(call -> exactSourceParseRead(List.of(call))).anyMatch(call -> {
                    String row = call.getArguments().get(1).toString().replace(".sourceFileId()", "");
                    Tree origin = definitions.get(row);
                    Set<String> seen = new LinkedHashSet<>();
                    while (origin != null && seen.add(origin.toString())) {
                        if (calls(origin).stream().anyMatch(query -> callName(query).equals("findCurrentByProjectIdAndCandidateId")
                                && compact(query.getArguments().toString()).equals("association.projectId(),candidate.id()"))) return true;
                        String receiver = origin.toString().split("\\.")[0];
                        origin = definitions.get(receiver);
                    }
                    return false;
                });
    }

    private static boolean throwsSupportException(MethodTree method) {
        List<ExpressionTree> thrown = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override public Void visitThrow(ThrowTree node, Void ignored) {
                thrown.add(node.getExpression()); return super.visitThrow(node, ignored);
            }
        }.scan(method.getBody(), null);
        return thrown.stream().anyMatch(value -> value instanceof NewClassTree created
                && created.getIdentifier().toString().equals("ContractCandidateSupportReplacementException")
                || value instanceof MethodInvocationTree call && call.getMethodSelect().toString()
                        .startsWith("ContractCandidateSupportReplacementException."));
    }

    private static void assertExecutedPolicy() throws Exception {
        assertExecutedPolicy(Class.forName("com.fuquan.audit.contract.application.project.extraction.ContractCandidateFactPolicy"));
    }

    private static Set<String> explicitPrimaryFactTypes() {
        return Set.of("CONTRACT_NO", "CONTRACT_NAME", "PARTY_A", "PARTY_B", "CREDIT_CODE",
                "AMOUNT_NUMERIC", "AMOUNT_UPPERCASE", "LINE_ITEM_KEY", "LINE_ITEM_NAME",
                "LINE_ITEM_QUANTITY", "LINE_ITEM_UNIT_PRICE", "LINE_ITEM_DECLARED_AMOUNT");
    }

    private static Set<String> supportFactTypes() {
        return Set.of("AWARD_AMOUNT", "AWARD_SUPPLIER", "AWARD_SUPPLIER_CREDIT_CODE");
    }

    private static Set<String> legacyFactTypes() {
        Set<String> facts = new LinkedHashSet<>(explicitPrimaryFactTypes());
        facts.addAll(supportFactTypes());
        return Set.copyOf(facts);
    }

    private static void assertExecutedPolicy(Class<?> type) throws Exception {
        if (Arrays.stream(type.getDeclaredMethods()).anyMatch(method -> method.getName().equals("authorizedFacts"))) {
            assertExecutedFactFiltering(type);
            return;
        }
        Method method = Arrays.stream(type.getDeclaredMethods()).filter(candidate -> candidate.getName().equals("allowedFactTypes")
                        && candidate.getParameterCount() == 2 && Arrays.stream(candidate.getParameterTypes()).allMatch(Class::isEnum))
                .findFirst().orElseThrow(() -> new AssertionError("active allowedFactTypes method absent"));
        assertThat(method.getParameterCount()).isEqualTo(2);
        method.setAccessible(true);
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object policy = constructor.newInstance();
        for (String mode : List.of("EXPLICIT_SUPPORT_AUTHORITY", "PRIMARY_ONLY_COMPAT")) {
            for (String role : List.of("CONTRACT_BODY", "AWARD_NOTICE")) {
                Object[] args = Arrays.stream(method.getParameterTypes()).map(parameter -> Arrays.stream(parameter.getEnumConstants())
                        .filter(value -> value.toString().equals(parameter.getSimpleName().equals("CandidateAuthorityMode")
                                ? mode : role)).findFirst().orElseThrow()).toArray();
                Set<String> expected = mode.equals("PRIMARY_ONLY_COMPAT")
                        ? (role.equals("CONTRACT_BODY") ? legacyFactTypes() : Set.of())
                        : (role.equals("CONTRACT_BODY") ? explicitPrimaryFactTypes() : supportFactTypes());
                assertThat(method.invoke(Modifier.isStatic(method.getModifiers()) ? null : policy, args))
                        .as("executed policy result for %s / %s", mode, role).isEqualTo(expected);
            }
        }
    }

    private static void assertExecutedFactFiltering(Class<?> type) throws Exception {
        var modeType = com.fuquan.audit.contract.domain.project.CandidateAuthorityMode.class;
        var method = type.getDeclaredMethod("authorizedFacts", ProjectFile.class, List.class, List.class, modeType);
        method.setAccessible(true);
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object policy = constructor.newInstance();
        ProjectFile primary = com.fuquan.audit.contract.application.project.parse.ProjectDocumentParserTest
                .file("contract.pdf", "application/pdf");
        ProjectFile support = com.fuquan.audit.contract.application.project.parse.ProjectDocumentParserTest
                .file("award.pdf", "application/pdf");
        List<String> probeTypes = new ArrayList<>(legacyFactTypes());
        probeTypes.add("UNRELATED");
        List<ExtractionResult> primaryFacts = probeTypes.stream()
                .map(fact -> legacyFact(primary, fact, "primary", null)).toList();
        List<ExtractionResult> supportFacts = probeTypes.stream()
                .map(fact -> legacyFact(support, fact, "support", null)).toList();
        for (var mode : modeType.getEnumConstants()) {
            for (var role : com.fuquan.audit.contract.domain.project.DocumentRole.values()) {
                var sources = List.of(new com.fuquan.audit.contract.application.project.extraction
                        .ContractCandidateFactPolicy.FactSource(role, support.id(), 1, supportFacts));
                @SuppressWarnings("unchecked")
                List<ExtractionResult> actual = (List<ExtractionResult>) method.invoke(policy, primary, primaryFacts, sources, mode);
                List<ExtractionResult> expected = new ArrayList<>();
                // Compatibility passes all primary rows to the existing assembler; its recognized output is
                // independently exercised by executesEveryLegacyAssemblerOverloadAgainstTheCurrentFactOutputBoundary.
                if (mode.name().equals("PRIMARY_ONLY_COMPAT")) expected.addAll(primaryFacts);
                else {
                    primaryFacts.stream().filter(fact -> explicitPrimaryFactTypes().contains(fact.factType())).forEach(expected::add);
                    if (role.name().equals("AWARD_NOTICE")) {
                        supportFacts.stream().filter(fact -> supportFactTypes().contains(fact.factType())).forEach(expected::add);
                    }
                }
                assertThat(actual).as("executed authorized facts for %s / %s", mode, role)
                        .containsExactlyInAnyOrderElementsOf(expected);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> documentedClassifications(String yaml) {
        Map<String, Object> document = new Yaml().load(yaml);
        Object route = at(document, "paths", "/api/v1/projects/{projectId}/contract-candidates/{candidateId}/support-authority-replacements",
                "post", "responses", "409");
        Object schema = at(resolve(document, route), "content", "application/json", "schema");
        Object data = at(resolve(document, schema), "properties", "data");
        Object classification = at(resolve(document, data), "properties", "classification");
        Object values = at(resolve(document, classification), "enum");
        return values instanceof List<?> list ? new LinkedHashSet<>((List<String>) list) : Set.of();
    }

    private static Object at(Object value, String... keys) {
        for (String key : keys) value = value instanceof Map<?, ?> map ? map.get(key) : null;
        return value;
    }

    private static Object resolve(Map<String, Object> document, Object value) {
        Set<String> seen = new LinkedHashSet<>();
        while (at(value, "$ref") instanceof String ref) {
            assertThat(ref).startsWith("#/");
            assertThat(seen.add(ref)).as("schema reference must not cycle").isTrue();
            value = at(document, ref.substring(2).split("/"));
        }
        return value;
    }

    private static void assertActualEndpointExceptionTransport(List<String> classifications) throws Exception {
        Class<?> controllerType = Class.forName("com.fuquan.audit.contract.api.project.ProjectController");
        Class<?> exceptionType = Class.forName("com.fuquan.audit.contract.application.project.ContractCandidateSupportReplacementException");
        assertEndpointExceptionTransport(controllerType, exceptionType, classifications);
    }

    private static void assertEndpointExceptionTransport(Class<?> controllerType, Class<?> exceptionType,
            List<String> classifications) throws Exception {
        Class<?> classificationType = Class.forName(exceptionType.getName() + "$Classification");
        assertThat(Arrays.stream(classificationType.getEnumConstants()).map(Object::toString).toList())
                .containsExactlyInAnyOrderElementsOf(classifications);
        Method endpoint = Arrays.stream(controllerType.getDeclaredMethods()).filter(method -> method.isAnnotationPresent(PostMapping.class))
                .filter(method -> Arrays.stream(method.getAnnotation(PostMapping.class).value())
                        .anyMatch(path -> path.endsWith("/support-authority-replacements"))).findFirst()
                .orElseThrow(() -> new AssertionError("annotated support endpoint absent"));
        assertThat(Arrays.stream(controllerType.getDeclaredMethods()).filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                .anyMatch(method -> Arrays.asList(method.getAnnotation(ExceptionHandler.class).value()).contains(exceptionType)))
                .as("exact thrown exception must have a controller-local handler").isTrue();
        for (String classification : classifications) {
            Object enumValue = Arrays.stream(classificationType.getEnumConstants())
                    .filter(value -> value.toString().equals(classification)).findFirst().orElseThrow();
            RuntimeException failure = (RuntimeException) Mockito.mock(exceptionType, invocation -> switch (invocation.getMethod().getName()) {
                case "classification" -> enumValue;
                case "errorCode" -> BusinessErrorCode.CONFLICT;
                case "getMessage" -> "architecture boundary probe";
                default -> Mockito.RETURNS_DEFAULTS.answer(invocation);
            });
            var constructor = controllerType.getConstructors()[0];
            List<Object> services = new ArrayList<>();
            Object[] dependencies = Arrays.stream(constructor.getParameterTypes()).map(type -> {
                if (type.getSimpleName().equals("ContractCandidateSupportReplacementService")) {
                    Object service = Mockito.mock(type, invocation -> { throw failure; });
                    services.add(service); return service;
                }
                return Mockito.mock(type, Mockito.RETURNS_DEEP_STUBS);
            }).toArray();
            assertThat(services).as("endpoint must inject the planned replacement service").hasSize(1);
            var mvc = MockMvcBuilders.standaloneSetup(constructor.newInstance(dependencies)).build();
            var response = mvc.perform(MockMvcRequestBuilders.post("/api/v1/projects/" + UUID.randomUUID()
                    + "/contract-candidates/" + UUID.randomUUID() + "/support-authority-replacements")
                    .header("Idempotency-Key", "architecture-probe").contentType("application/json")
                    .content("{\"expectedCandidateVersion\":0,\"supportFileIds\":[]}"))
                    .andReturn();
            assertThat(Mockito.mockingDetails(services.get(0)).getInvocations())
                    .as("real POST must call replacement service").isNotEmpty();
            assertThat(response.getResolvedException()).as("exact exception instance must reach local handler").isSameAs(failure);
            assertThat(response.getResponse().getStatus()).isEqualTo(409);
            var json = new ObjectMapper().readTree(response.getResponse().getContentAsString());
            assertThat(json.path("data").size()).isEqualTo(1);
            assertThat(json.path("data").path("classification").asText()).isEqualTo(classification);
        }
    }

    private static void assertFrontendClassificationBoundary(Path operation, Path flow) throws Exception {
        String script = """
                const fs = require('node:fs'), vm = require('node:vm'), path = require('node:path');
                const { stripTypeScriptTypes } = require('node:module');
                const assert = require('node:assert/strict');
                const operation = path.resolve(process.argv[2]), flow = path.resolve(process.argv[3]);
                const strip = p => stripTypeScriptTypes(fs.readFileSync(p, 'utf8'));
                // Lex tokens preserve quoted values and discard comments, so strings/comments cannot impersonate code.
                const lex = source => [...source.matchAll(/\\/\\*[\\s\\S]*?\\*\\/|\\/\\/[^\\n]*|"(?:\\\\.|[^"\\\\])*"|'(?:\\\\.|[^'\\\\])*'|[A-Za-z_$][\\w$]*|===|!==|=>|\\S/g)]
                  .filter(m => !m[0].startsWith('//') && !m[0].startsWith('/*')).map(m => m[0]);
                function block(tokens, start, open, close) {
                  let depth = 0;
                  for (let i = start; i < tokens.length; i++) {
                    if (tokens[i] === open) depth++;
                    if (tokens[i] === close && --depth === 0) return tokens.slice(start + 1, i);
                  }
                  throw Error('unbalanced boundary contract');
                }
                function checkFlow(source) {
                  const t = lex(source), f = t.findIndex((v, i) => v === 'function' && t[i+1] === 'replaceCandidateSupportAndReconcile');
                  assert(f >= 0, 'active exported replacement function is required');
                  assert(t.some((v, i) => v === 'import' && t[i+1] === '{' &&
                    t.slice(i+2, t.indexOf('}', i+2)).includes('parseCandidateSupportAuthorityClassification') &&
                    /['\"]\\.\\/candidateSupportAuthorityOperation(?:\\.ts)?['\"]/.test(t[t.indexOf('}', i+2)+2])),
                    'active flow must import the tested operation parser');
                  for (let i = 0; i < t.length; i++) if (t[i] === 'parseCandidateSupportAuthorityClassification') {
                    assert(!['function', 'const', 'let', 'var', 'as'].includes(t[i-1]) && t[i+1] !== '=',
                      'the imported parser may not be shadowed or rebound');
                  }
                  const body = block(t, t.indexOf('{', f), '{', '}');
                  assert(!body.some(v => ['eval', 'Function', 'arguments'].includes(v)), 'dynamic error access is forbidden');
                  let consumed = false;
                  for (let i = 0; i < body.length; i++) {
                    if (body[i] !== 'catch') continue;
                    assert.notEqual(body[i-1], '.', 'promise catch helpers must not hide recovery decisions');
                    assert.equal(body[i+1], '('); const error = body[i+2]; assert.equal(body[i+3], ')');
                    const caught = block(body, i+4, '{', '}');
                    for (let j = 0; j < caught.length; j++) {
                      if (caught[j] !== error) continue;
                      const parserArgument = caught[j-2] === 'parseCandidateSupportAuthorityClassification'
                        && caught[j-1] === '(' && caught[j+1] === ')';
                      const rethrow = caught[j-1] === 'throw' && [';', '}'].includes(caught[j+1] ?? '}');
                      assert(parserArgument || rethrow, 'caught error may only enter the classification parser or be rethrown; aliases/coercion/helpers forbidden');
                      if (parserArgument) {
                        assert.equal(caught[j-3], '='); const classified = caught[j-4];
                        assert(['const', 'let'].includes(caught[j-5]));
                        assert(caught.some((v, k) => v === classified && k !== j-4 &&
                          (['===', '!=='].includes(caught[k+1]) || caught[k-2] === 'switch')),
                          'active decision must use the parser result');
                        consumed = true;
                      }
                    }
                  }
                  assert(consumed, 'active replacement flow must classify its caught POST error');
                }
                // Prove the flow contract rejects aliases, coercion, and dynamic helper handoff (not finite message spellings).
                const good = 'import { parseCandidateSupportAuthorityClassification } from "./candidateSupportAuthorityOperation.ts"; export async function replaceCandidateSupportAndReconcile() { try { await post(); } catch (e) { const c = parseCandidateSupportAuthorityClassification(e); if (c === "REQUEST_REJECTED") clear(); throw e; } }';
                checkFlow(good);
                for (const bad of ['const a = e; a["mes" + "sage"];', 'e.toString();', 'helper(e);', 'String(e);']) {
                  assert.throws(() => checkFlow(good.replace('throw e;', bad + 'throw e;')));
                }
                checkFlow(strip(flow));
                // Load the real operation module and its local dependencies, keeping only the shared browser HTTP
                // module out of Node's import.meta.env path. Its ApiError class body is unchanged and evaluated below.
                const client = path.resolve(path.dirname(operation), '../../api/client.ts');
                const clientSource = strip(client), classStart = clientSource.indexOf('export class ApiError');
                assert(classStart >= 0);
                const tail = clientSource.slice(classStart).replace(/^export /, '');
                let end = tail.indexOf('{'), depth = 1, i = end + 1;
                for (; depth && i < tail.length; i++) { if (tail[i] === '{') depth++; if (tail[i] === '}') depth--; }
                const ApiError = vm.runInThisContext('(' + tail.slice(0, i) + ')');
                const modules = new Map();
                async function load(file) {
                  if (modules.has(file)) return modules.get(file);
                  let mod;
                  if (file === client) mod = new vm.SyntheticModule(['ApiError'], function () { this.setExport('ApiError', ApiError); });
                  else mod = new vm.SourceTextModule(strip(file), { identifier: file });
                  modules.set(file, mod);
                  await mod.link(spec => { assert(spec.startsWith('.'), 'bounded recovery dependencies must be local');
                    let p = path.resolve(path.dirname(file), spec); if (!path.extname(p)) p += '.ts'; return load(p); });
                  return mod;
                }
                (async () => {
                  const mod = await load(operation); await mod.evaluate();
                  const parse = mod.namespace.parseCandidateSupportAuthorityClassification;
                  assert.equal(typeof parse, 'function');
                  const values = ['OUTCOME_UNCERTAIN', 'REQUEST_REJECTED', 'RESULT_SUPERSEDED', 'INTEGRITY_CONFLICT'];
                  const guarded = data => new Proxy(new ApiError(409, 'BUSINESS_ERROR', values.join(' '), data), {
                    get(target, key) { assert.equal(key, 'data', 'parser must not read ANY other error property/coercion'); return target.data; }
                  });
                  for (const value of values) assert.equal(parse(guarded({ classification: value })), value);
                  for (const data of [undefined, null, [], {}, {classification: 'FIFTH'}, {classification: 409},
                    {classification: {toString() {throw Error('coercion forbidden');}}}, ...values.map(value => value)]) {
                    assert([null, undefined].includes(parse(guarded(data))), 'unclassified data must remain unknown');
                  }
                  for (const value of values) {
                    assert([null, undefined].includes(parse(new Error(value))));
                    assert([null, undefined].includes(parse({data: {classification: value}})));
                  }
                  console.log('frontend classification boundary PASS');
                })().catch(error => { console.error(error); process.exitCode = 1; });
                """;
        Path probe = Files.createTempFile(Path.of("target"), "candidate-authority-boundary-", ".cjs");
        try {
            Files.writeString(probe, script);
            Process process = new ProcessBuilder("node", "--experimental-vm-modules", probe.toAbsolutePath().toString(),
                    operation.toAbsolutePath().toString(), flow.toAbsolutePath().toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor()).as("executable frontend classification boundary: %s", output).isZero();
            assertThat(output).as("Node must finish its asynchronous parser probes").contains("frontend classification boundary PASS");
        } finally { Files.deleteIfExists(probe); }
    }

    private static boolean migrationReference(String text) {
        // Resolve bounded string aliases before normalizing punctuation; mapper DML is not migration execution.
        Map<String, String> definitions = new java.util.LinkedHashMap<>();
        Matcher declarations = Pattern.compile("\\b(?:String|var)\\s+(\\w+)\\s*=\\s*([^;\\r\\n]+);").matcher(text);
        while (declarations.find()) definitions.put(declarations.group(1), declarations.group(2));
        Map<String, String> aliases = new java.util.LinkedHashMap<>();
        for (int pass = 0; pass < definitions.size(); pass++) {
            int previousSize = aliases.size();
            definitions.forEach((name, expression) -> {
                if (aliases.containsKey(name)) return;
                StringBuilder value = new StringBuilder();
                for (String part : expression.split("\\s*\\+\\s*")) {
                    part = part.strip();
                    if (part.matches("\"[^\"]*\"|'[^']*'")) value.append(part, 1, part.length() - 1);
                    else if (aliases.containsKey(part)) value.append(aliases.get(part));
                    else return; // Unknown expressions and cycles cannot expand into unbounded source text.
                }
                aliases.put(name, value.toString());
            });
            if (aliases.size() == previousSize) break;
        }
        String expanded = expandStringAliases(text, aliases);
        String normalized = text.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        Matcher strings = Pattern.compile("[\"']([^\"']*)[\"']").matcher(text);
        StringBuilder literals = new StringBuilder();
        while (strings.find()) literals.append(strings.group(1));
        normalized += literals.toString().replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        String ddl = expanded.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return normalized.contains("contractcontractcandidatesupportauthoritysql")
                || normalized.contains("migrationidcontractcandidatesupportauthority")
                || Pattern.compile("(?:create|alter|drop|truncate)table(?:ifnotexists|ifexists)?contractcandidatesupportauthority")
                        .matcher(ddl).find()
                || Pattern.compile("(?<![A-Za-z0-9_-])contract-candidate-support-authority(?![A-Za-z0-9_-])")
                        .matcher(text).find();
    }

    private static String expandStringAliases(String text, Map<String, String> aliases) {
        Matcher token = Pattern.compile("\"[^\"]*\"|'[^']*'|\\b([A-Za-z_$][A-Za-z0-9_$]*)\\b").matcher(text);
        StringBuilder expanded = new StringBuilder();
        while (token.find()) token.appendReplacement(expanded, Matcher.quoteReplacement(
                token.group(1) == null ? token.group() : aliases.getOrDefault(token.group(1), token.group())));
        token.appendTail(expanded);
        return expanded.toString();
    }

    private static boolean isReadOnlyMigrationConsumer(Path path, String text) {
        if (!path.normalize().startsWith(TEST) || !text.contains("class ")) return false;
        // Only source readers and assertions are permitted to consume this manual migration.
        // Unknown helpers, method references, JDBC aliases, reflection and process/shell launch fail closed.
        Set<String> allowed = Set.of("of", "readString", "readAllLines", "readAllBytes", "resolve", "toString",
                "exists", "isDirectory", "toAbsolutePath", "getParent", "subList", "containsExactly", "isGreaterThan",
                "assertThat", "as", "contains", "doesNotContain", "containsIgnoringCase", "isEqualTo", "isTrue",
                "isFalse", "isNotNull", "isNotEmpty", "isEmpty", "hasSize", "hasSizeGreaterThan", "matches",
                "replace", "replaceAll", "split", "trim", "strip", "toUpperCase", "toLowerCase", "substring",
                "indexOf", "lastIndexOf", "startsWith", "endsWith", "containsPattern", "doesNotMatch");
        List<MethodTree> methods = javaMethods(text);
        Set<String> local = new LinkedHashSet<>();
        methods.forEach(method -> local.add(method.getName().toString()));
        Set<String> classNames = topLevelClassNames(text);
        Set<String> localReceivers = migrationLocalReceivers(text, classNames);
        List<Tree> members = migrationConnectedMembers(text);
        Set<String> usedIdentifiers = new LinkedHashSet<>();
        List<NewClassTree> constructions = new ArrayList<>();
        TreeScanner<Void, Void> scanner = new TreeScanner<>() {
            @Override public Void visitIdentifier(IdentifierTree node, Void ignored) {
                usedIdentifiers.add(node.getName().toString()); return super.visitIdentifier(node, ignored);
            }
            @Override public Void visitNewClass(NewClassTree node, Void ignored) {
                constructions.add(node); return super.visitNewClass(node, ignored);
            }
        };
        members.forEach(member -> scanner.scan(member, null));
        Matcher imports = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([^;]+);").matcher(text);
        while (imports.find()) {
            String imported = imports.group(1);
            String name = imported.substring(imported.lastIndexOf('.') + 1);
            if ((name.equals("*") || usedIdentifiers.contains(name))
                    && !imported.matches("(?:java\\.(?:nio\\.(?:file|charset)|util)|org\\.(?:junit|assertj))\\..*")) return false;
        }
        String connected = members.toString();
        return members.stream().allMatch(member -> calls(member).stream()
                .allMatch(call -> isMigrationLocalCall(call, classNames, localReceivers) && local.contains(callName(call))
                        ? members.stream().anyMatch(target -> target instanceof MethodTree method
                                && method.getName().contentEquals(callName(call)))
                        : allowed.contains(callName(call))))
                && constructions.stream().allMatch(created -> created.getIdentifier().toString().equals("String"))
                && !connected.contains("::") && !text.contains("@Sql") && !text.contains("@SpringBootTest")
                && !connected.contains("new ProcessBuilder") && !connected.contains("Class.forName");
    }

    private static List<Tree> migrationConnectedMembers(String text) {
        List<Tree> members = javaMembers(text);
        Set<String> classNames = topLevelClassNames(text);
        Set<String> localReceivers = migrationLocalReceivers(text, classNames);
        Set<String> localMethods = new LinkedHashSet<>();
        members.stream().filter(MethodTree.class::isInstance).map(MethodTree.class::cast)
                .map(method -> method.getName().toString()).forEach(localMethods::add);
        Set<Tree> connected = new LinkedHashSet<>();
        // Always inspect initializers, fields, constructors and nested declarations. A mixed compatibility
        // test may also exercise domain behavior, but no migration-connected helper/caller is exempt.
        members.stream().filter(member -> !(member instanceof MethodTree method)
                || method.getReturnType() == null || migrationReference(member.toString())).forEach(connected::add);
        boolean changed;
        do {
            int previous = connected.size();
            Set<String> names = new LinkedHashSet<>();
            connected.forEach(member -> {
                if (member instanceof MethodTree method) names.add(method.getName().toString());
                if (member instanceof VariableTree field) names.add(field.getName().toString());
                calls(member).stream().filter(call -> isMigrationLocalCall(call, classNames, localReceivers))
                        .map(CandidateCrossMaterialAuthorityArchitectureGuardTest::callName)
                        .filter(localMethods::contains).forEach(names::add);
            });
            for (Tree member : members) {
                if (member instanceof MethodTree method && (names.contains(method.getName().toString())
                        || referencesAny(member, names))) connected.add(member);
            }
            changed = previous != connected.size();
        } while (changed);
        return List.copyOf(connected);
    }

    private static Set<String> topLevelClassNames(String source) {
        Set<String> names = new LinkedHashSet<>();
        javaClasses(source).forEach(type -> names.add(type.getSimpleName().toString()));
        return names;
    }

    private static Set<String> migrationLocalReceivers(String source, Set<String> classNames) {
        Set<String> receivers = new LinkedHashSet<>(Set.of("this"));
        List<VariableTree> variables = new ArrayList<>();
        TreeScanner<Void, Void> scanner = new TreeScanner<>() {
            @Override public Void visitVariable(VariableTree variable, Void ignored) {
                variables.add(variable); return super.visitVariable(variable, ignored);
            }
        };
        javaMembers(source).forEach(member -> scanner.scan(member, null));
        boolean changed;
        do {
            int previous = receivers.size();
            variables.stream().filter(variable -> variable.getType() != null
                            && namesLocalClass(variable.getType().toString(), classNames)
                            || variable.getInitializer() != null && receivers.contains(variable.getInitializer().toString()))
                    .map(variable -> variable.getName().toString()).forEach(receivers::add);
            changed = previous != receivers.size();
        } while (changed);
        return receivers;
    }

    private static boolean namesLocalClass(String name, Set<String> classNames) {
        return classNames.stream().anyMatch(type -> name.equals(type) || name.endsWith("." + type));
    }

    private static boolean isMigrationLocalCall(MethodInvocationTree call, Set<String> classNames,
            Set<String> receivers) {
        if (isLocalCall(call)) return true;
        if (!(call.getMethodSelect() instanceof MemberSelectTree select)) return false;
        ExpressionTree receiver = select.getExpression();
        while (receiver instanceof ParenthesizedTree wrapped) receiver = wrapped.getExpression();
        return namesLocalClass(receiver.toString(), classNames) || receivers.contains(receiver.toString());
    }

    private static boolean referencesAny(Tree tree, Set<String> names) {
        boolean[] found = {false};
        new TreeScanner<Void, Void>() {
            @Override public Void visitIdentifier(IdentifierTree node, Void ignored) {
                found[0] |= names.contains(node.getName().toString()); return super.visitIdentifier(node, ignored);
            }
            @Override public Void visitMemberSelect(MemberSelectTree node, Void ignored) {
                found[0] |= names.contains(node.getIdentifier().toString()); return super.visitMemberSelect(node, ignored);
            }
            @Override public Void visitMemberReference(MemberReferenceTree node, Void ignored) {
                found[0] |= names.contains(node.getName().toString()); return super.visitMemberReference(node, ignored);
            }
        }.scan(tree, null);
        return found[0];
    }

    private static boolean sourceExists(String relativePath) {
        return Files.isRegularFile(MAIN.resolve(relativePath));
    }

    private static Map<String, String> migrationFingerprints() {
        return Map.ofEntries(
                Map.entry("V001__audit_task_baseline.sql", "477c584d77f2ce266ff948929bd7273d7136e0bb6e394d14cdad6b5eca677ace"),
                Map.entry("V002__local_account_auth_observability.sql", "e5baf41cc483a87d90ad4907dad8cfb3d901cf0637dd818eca34d505a4bfabed"),
                Map.entry("V003_f02_seed.sql", "6ecb85563546d4b9c0c20a8a3dcee06a2618d6dc60ee43f67c68df4285af19f1"),
                Map.entry("V003__f02_contract_review.sql", "41abed34454fcf19bee25fe4059e45012a7f53977aaff049ce6c72c80d41f090"),
                Map.entry("V004__contract_review_session.sql", "5ff409e24d617ba47dbc377c20ea415cc654dd48a86c484406d2187196b0dd2d"),
                Map.entry("V005__contract_review_session_soft_delete.sql", "f8282bd3d6648dc2d5a9edd6cfaa70a469e927aca8d0dfc77fcc5ebe961d76f5"),
                Map.entry("V006__f01_budget_approval.sql", "506c2400740d9655d62cc10cb86526b02a78d2a0c5c40c7671b539787dff1506"),
                Map.entry("V007__f01_material_review.sql", "d53d1b462acd823e0238795f8cb57cc8c1ec53ecc6ad70218bdba59dedcec448"),
                Map.entry("V008__f01_material_check_fields.sql", "370083baa134d8c2a56454df98cb26e97496832eb635def55b59294a63d236c1"),
                Map.entry("V009__review_finding_acknowledgement.sql", "5af95e816f9f3d309eb960f86c49dca9cc2f197e330befffd37817ea9e5f13ad"),
                Map.entry("V010__project_intake_core.sql", "b8f0f66524226bd1e74a2273e5178ace3d807d8ccc9cdb3f43428aacc5b2a350"),
                Map.entry("V011__project_contract_association.sql", "3116319c1ace0249bd4ba2efca9c0e5b795b6713ecf160f57911be385c5820b0"),
                Map.entry("V012__f01_validation_risk_fields.sql", "86159d1ab93259721b3df68c5c064a7564e5728916538e3dbf5d17f64b33560d"),
                Map.entry("V013__f01_validation_confirm_log.sql", "293dbac76c942273b6bc36bb76e26c7a865bcde01b0cac33d41aa10050d9ee54"),
                Map.entry("V014__f01_project_and_year_validation_config.sql", "cc4acadf2ea6ad62e572e7101295b9280df0bc60cc396ce5d8aecc0b4809c41b"),
                Map.entry("V015__f01_category_rules.sql", "aed22cdbf4d6ca2bfcd03f0f99ec7a783cde24c9ba983b9de51cc173b209dc11"),
                Map.entry("V016__f04_budget_approval_program.sql", "fef84befeda249b14de54a708f47938bc94713dd4b3759069fc581cb58251369"),
                Map.entry("V017__project_file_parse_provenance.sql", "a81a29a84db6d8d90e453c93e6ddfd11d782733c82130c6ee78d24f35d64bc60"),
                Map.entry("V018__f03_blacklist.sql", "1452e9460ea4ee10c6f73af613f19e3f84e08c114307f37302f77d45ac4bb6ff"),
                Map.entry("V019__f03_blacklist_operation_log.sql", "c59378ca875b892ea659eeff6b179ff1c6b0d6edc50d597271ac525cb5b3c28c"),
                Map.entry("V020__f01_budget_semantic_analysis.sql", "fff7aca33521ff29b265055d0a72570dcc7190b7f982effcbee4c3c31d160cf0"),
                Map.entry("V021__f01_ai_provenance.sql", "7f642aa630dea8b3b1eef3d42bb7425929210bb5fd0c017cb9b27e334acf89e9"),
                Map.entry("V022__f01_ai_node_results.sql", "83b2fbd449103cf6c75e784b75932b3b67c763593d8487b62d0ab364e8b641a8"),
                Map.entry("CONTRACT__contract_candidate_authority.sql", "43d768ac6e5ad5ed5ac8cbd4cc5733abeaed93a6e4b402bdd5df6be672da3a34"),
                Map.entry("CONTRACT__contract_candidate_confirmation.sql", "abc5f95ad4f042a4619481b6bdfe4e1389c954fa3e01dfa5abdc7bebee44d593"),
                Map.entry("CONTRACT__project_file_identity_assessment.sql", "5fafa86ffcf9a6fc826ea3977854260b3533645e30280a60a28cdc3677cf4f91"),
                Map.entry("CONTRACT__project_file_parse_result.sql", "196dc7fb95e85573a838495a87f0a5fd4c0fe20b143a61d30d8ca63800ba53bc"),
                Map.entry("CONTRACT__project_file_role_assessment.sql", "a599bd29c2b36bf5aaa0ba9c2d7300ad2cc98bc544130328cb0573b435d98cd8"),
                Map.entry("CONTRACT__review_ack_report.sql", "3d8bbde1fb7153a812ec0f2442309676e8f9045c53e905eccab7a52d7e7835bf"),
                Map.entry("CONTRACT__review_run.sql", "56c5cd355f3bd981bacacad296edd7c6de3a501de9d9bd27254af6542f68b1b1"),
                Map.entry("CONTRACT__review_run_results.sql", "cc4babc62c8f43e86d8d3c2dd16b394b1fb6acd708f454c4cb49b8b7ae0a0c91"),
                Map.entry("CONTRACT__review_session_project_file_binding.sql", "44bfa967dea84690cb9b17d2b1bd3088d4e4c8f9e6554bcb103285bb43f8465e"),
                Map.entry("CONTRACT__sanitized_debug_bundle.sql", "a38c6f64678b5115320fd2714b9bef1522a36dbb71c310f903b54ae0303d63ae"));
    }

    private static String source(String relativePath) {
        return source(MAIN.resolve(relativePath));
    }

    private static String source(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path) : "";
        } catch (IOException failure) {
            throw new AssertionError("cannot read architecture assertion target " + path, failure);
        }
    }

    private static String allJavaSource() throws IOException {
        return allText(MAIN, ".java");
    }

    private static String allText(Path root) throws IOException {
        return allText(root, "");
    }

    private static String allText(Path root, String requiredSuffix) throws IOException {
        if (!Files.isDirectory(root)) return "";
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> requiredSuffix.isEmpty() || path.toString().endsWith(requiredSuffix))
                    .map(CandidateCrossMaterialAuthorityArchitectureGuardTest::source)
                    .reduce("", String::concat);
        }
    }

    private static Set<String> enumMembers(String source, String declaration) {
        int declarationIndex = source.indexOf(declaration);
        if (declarationIndex < 0) return Set.of();
        int open = source.indexOf('{', declarationIndex);
        int close = source.indexOf('}', open + 1);
        if (open < 0 || close < 0) return Set.of();
        return upperCaseTokens(source.substring(open + 1, close));
    }

    private static Set<String> yamlEnumMembers(String source) {
        int enumIndex = source.indexOf("enum:");
        if (enumIndex < 0) return Set.of();
        return upperCaseTokens(source.substring(enumIndex));
    }

    private static Set<String> upperCaseTokens(String source) {
        Matcher matcher = Pattern.compile("\\b[A-Z][A-Z_]+\\b").matcher(source);
        Set<String> values = new LinkedHashSet<>();
        while (matcher.find()) values.add(matcher.group());
        return values;
    }

    private static boolean containsAll(String source, String... required) {
        return Stream.of(required).allMatch(source::contains);
    }

    private static String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        if (startIndex < 0) return "";
        int endIndex = source.indexOf(end, startIndex + start.length());
        return endIndex < 0 ? source.substring(startIndex) : source.substring(startIndex, endIndex);
    }

    private static String schemaBlock(String source, String schemaName) {
        Pattern schemaStart = Pattern.compile("(?m)^ {4}(?! )" + Pattern.quote(schemaName) + ":\\s*$");
        Matcher start = schemaStart.matcher(source);
        if (!start.find()) return "";
        Matcher next = Pattern.compile("(?m)^ {4}(?! )[^\\s].*:").matcher(source);
        return next.find(start.end()) ? source.substring(start.start(), next.start()) : source.substring(start.start());
    }

    private static String methodBlock(String source, String methodMarker) {
        return javaMethods(source).stream().filter(method -> method.getName().contentEquals(methodMarker))
                .map(Object::toString).findFirst().orElse("");
    }

    private static String fieldName(String source, String type) {
        return javaMembers(source).stream().filter(VariableTree.class::isInstance).map(VariableTree.class::cast)
                .filter(field -> field.getType().toString().equals(type)).map(field -> field.getName().toString())
                .findFirst().orElse("");
    }

    private static String compact(String source) {
        return source.replaceAll("\\s+", "");
    }

    private static List<Path> migrationConsumers() throws IOException {
        List<Path> consumers = new ArrayList<>();
        // Includes root start-local-dev.{sh,bat}, .mvn, arbitrary extension resources and nested test helpers.
        // Generated build output and downloaded libraries are not startup source surfaces.
        try (Stream<Path> paths = Files.walk(Path.of("."))) {
            paths.filter(path -> !generatedBuildOutput(path)).filter(Files::isRegularFile)
                    .forEach(path -> addMigrationConsumer(consumers, path));
        }
        return consumers;
    }

    private static boolean generatedBuildOutput(Path path) {
        Path normalized = path.normalize();
        if (normalized.getNameCount() == 0) return false;
        String root = normalized.getName(0).toString();
        return root.equals("target") || root.startsWith("target-");
    }

    @Test
    void generatedBuildDirectoriesDoNotHideActualSourceOrResourceMigrationConsumers() {
        assertThat(generatedBuildOutput(Path.of("target", "classes", "seed.sql"))).isTrue();
        assertThat(generatedBuildOutput(Path.of("target-deploy26", "classes", "seed.sql"))).isTrue();
        assertThat(generatedBuildOutput(Path.of("src", "main", "resources", "target-cache", "seed.data"))).isFalse();
        assertThat(generatedBuildOutput(Path.of(".mvn", "startup.options"))).isFalse();
        assertThat(generatedBuildOutput(Path.of("start-local-dev.bat"))).isFalse();
    }

    private static void addMigrationConsumer(List<Path> consumers, Path path) {
        if (path.getFileName().toString().equals("CandidateCrossMaterialAuthorityArchitectureGuardTest.java")) return;
        String text = readableSource(path);
        if (migrationReference(text)) consumers.add(path);
    }

    private static String readableSource(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            for (byte value : bytes) {
                if (value == 0) return "";
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AssertionError("cannot inspect bounded backend surface " + path, failure);
        }
    }

    private static String sha256(Path path) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path)));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new AssertionError("cannot fingerprint protected P1-B boundary " + path, failure);
        }
    }
}
