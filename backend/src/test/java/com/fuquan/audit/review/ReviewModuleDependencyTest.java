package com.fuquan.audit.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewModuleDependencyTest {

    @Test
    void sharedReviewModuleDoesNotImportContractModule() throws Exception {
        Path reviewSource = Path.of("src", "main", "java", "com", "fuquan", "audit", "review");
        List<Path> illegalImports;
        try (var files = Files.walk(reviewSource)) {
            illegalImports = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> containsContractImport(path))
                    .toList();
        }

        assertThat(illegalImports).isEmpty();
    }

    @Test
    void sharedReviewModuleDoesNotOwnContractSpecificProgressSemantics() throws Exception {
        Path reviewSource = Path.of("src", "main", "java", "com", "fuquan", "audit", "review");
        List<Path> illegalOwners;
        try (var files = Files.walk(reviewSource)) {
            illegalOwners = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> containsAny(
                            path,
                            "F02ReviewProgress",
                            "SOURCE_PREPARATION",
                            "F02RuleCodes"))
                    .toList();
        }

        assertThat(illegalOwners).isEmpty();
    }

    private boolean containsContractImport(Path path) {
        return containsAny(path, "com.fuquan.audit.contract");
    }

    private boolean containsAny(Path path, String... forbiddenFragments) {
        try {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            return java.util.Arrays.stream(forbiddenFragments).anyMatch(source::contains);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
