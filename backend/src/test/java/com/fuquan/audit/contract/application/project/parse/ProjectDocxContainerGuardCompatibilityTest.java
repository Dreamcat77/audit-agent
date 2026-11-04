package com.fuquan.audit.contract.application.project.parse;

import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader;
import com.fuquan.audit.contract.test.ProjectFileFixtures;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectDocxContainerGuardCompatibilityTest {
    private final ProjectIntakeProperties limits = ProjectIntakeProperties.defaults();
    private final ProjectDocxContainerGuard guard =
            new ProjectDocxContainerGuard(new SelectedProjectArchiveReader(limits), limits);

    @Test
    void ordinaryAndPermissionOnlyUnixDocxContainersAreAccepted() {
        byte[] ordinary = ProjectFileFixtures.bytes("valid.docx");
        byte[] permissionOnly = ProjectFileFixtures.bytes("valid-unix-0600.docx");

        assertThat(guard.validate(new ByteArrayInputStream(ordinary))).isEqualTo(ordinary);
        assertThat(guard.validate(new ByteArrayInputStream(permissionOnly))).isEqualTo(permissionOnly);
    }

    @Test
    void missingRequiredOoxmlPartsHaveStableSafeReason() {
        byte[] malformed = ProjectFileFixtures.bytes("malformed-docx-missing-parts.docx");

        assertThatThrownBy(() -> guard.validate(new ByteArrayInputStream(malformed)))
                .isInstanceOfSatisfying(ProjectParseException.class,
                        failure -> assertThat(failure.reasonCode()).isEqualTo("DOCX_REQUIRED_PART_MISSING"));
    }

    @Test
    void unsafeOrMalformedDocxContainersHaveStableSafeReasons() {
        assertReason("symlink.zip", "DOCX_SYMLINK_ENTRY");
        assertReason("non-regular.zip", "DOCX_NON_REGULAR_ENTRY");
        assertReason("encrypted.zip", "DOCX_ENCRYPTED_OR_UNSUPPORTED");
        assertReason("truncated.zip", "DOCX_MALFORMED_OR_TRUNCATED");
    }

    private void assertReason(String fixture, String reasonCode) {
        assertThatThrownBy(() -> guard.validate(new ByteArrayInputStream(ProjectFileFixtures.archiveBytes(fixture))))
                .as(fixture)
                .isInstanceOfSatisfying(ProjectParseException.class,
                        failure -> assertThat(failure.reasonCode()).isEqualTo(reasonCode));
    }
}
