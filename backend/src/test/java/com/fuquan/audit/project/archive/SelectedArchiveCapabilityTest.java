package com.fuquan.audit.project.archive;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SelectedArchiveCapabilityTest {

    @Test
    void selectedReaderProvesEverySecurityCriticalCapabilityUsingRealArchiveBytes() {
        assertThat(List.of("encrypted.zip", "truncated.zip", "crc-mismatch.zip", "symlink.zip",
                "non-regular.zip", "high-ratio.zip"))
                .allSatisfy(name -> assertThat(ArchiveFixtureResources.fixed(name)).isNotEmpty());
        String reader = System.getProperty("archive.reader", "commons-compress");
        Map<String, ArchiveCapabilityProbe.Result> results = new ArchiveCapabilityProbe().probe(reader);

        assertThat(results)
                .as("archive reader %s capability matrix", reader)
                .allSatisfy((cell, result) -> assertThat(result)
                        .as(cell)
                        .isEqualTo(ArchiveCapabilityProbe.Result.PROVEN));
    }
}
