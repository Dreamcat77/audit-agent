package com.fuquan.audit.contract.application.project;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectIdentityFactoryTest {

    @Test
    void storageKeyUsesOnlyPreallocatedFileIdAndStagedHash() {
        UUID id = UUID.fromString("10000000-0000-0000-0000-000000000001");
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        String first = new ProjectFileStorageKeyFactory().create(id, hash).value();
        String replay = new ProjectFileStorageKeyFactory().create(id, hash).value();

        assertThat(first).isEqualTo(replay).matches("[0-9a-f]{64}");
        assertThat(new ProjectFileStorageKeyFactory().create(UUID.randomUUID(), hash).value()).isNotEqualTo(first);
        assertThatThrownBy(() -> new ProjectFileStorageKeyFactory().create(id, "bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractionKeyIsLengthDelimitedAndBindsLocatorButNotCandidate() {
        ExtractionKeyFactory factory = new ExtractionKeyFactory();
        UUID source = UUID.fromString("20000000-0000-0000-0000-000000000002");
        String one = factory.create(source, "project-extraction-v1", "CONTRACT_NO", null, "page:1/occurrence:1");
        String replay = factory.create(source, "project-extraction-v1", "CONTRACT_NO", null, "page:1/occurrence:1");

        assertThat(one).isEqualTo("1c1c34710e4ae9e8466dc7d496154d012ac4134c0b353841f0ddc85378b7b0e2")
                .isEqualTo(replay).matches("[0-9a-f]{64}");
        assertThat(factory.create(source, "project-extraction-v1", "CONTRACT_NO", "", "page:1/occurrence:1"))
                .isNotEqualTo(one);
        assertThat(factory.create(source, "project-extraction-v1", "CONTRACT_NO", null, "page:1/occurrence:2"))
                .isNotEqualTo(one);
    }
}
