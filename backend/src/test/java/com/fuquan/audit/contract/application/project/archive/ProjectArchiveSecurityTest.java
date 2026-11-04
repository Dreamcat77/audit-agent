package com.fuquan.audit.contract.application.project.archive;

import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectArchiveSecurityTest {
    private final SelectedProjectArchiveReader reader =
            new SelectedProjectArchiveReader(ProjectIntakeProperties.defaults());

    @Test
    void readsOrdinaryRealZipAndRejectsTraversalAndCaseFoldCollision() throws Exception {
        List<ProjectArchiveReader.Entry> entries = new ArrayList<>();
        reader.read(new ByteArrayInputStream(zip("folder/contract.pdf")), entries::add);
        assertThat(entries)
                .singleElement().extracting(ProjectArchiveReader.Entry::normalizedPath)
                .isEqualTo("folder/contract.pdf");
        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(zip("../escape.txt")), ignored -> { }))
                .isInstanceOf(ProjectArchiveSecurityException.class)
                .hasMessageContaining("PATH_TRAVERSAL");
        assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(zip("A.txt", "a.TXT")), ignored -> { }))
                .isInstanceOf(ProjectArchiveSecurityException.class)
                .hasMessageContaining("PATH_COLLISION");
    }

    @Test
    void exposesEachEntryAsAStreamRatherThanMaterializedBytes() throws Exception {
        List<Class<?>> contentTypes = new ArrayList<>();
        reader.read(new ByteArrayInputStream(zip("first.txt", "second.txt")), entry -> {
            contentTypes.add(entry.content().getClass());
            try { entry.content().transferTo(java.io.OutputStream.nullOutputStream()); }
            catch (java.io.IOException failure) { throw new AssertionError(failure); }
        });

        assertThat(contentTypes).hasSize(2).noneMatch(ByteArrayInputStream.class::equals);
    }

    @Test
    void rejectsAbsoluteDriveAndUncEntryPaths() throws Exception {
        for (String path : List.of("/absolute/evil.pdf", "C:\\evil.pdf", "\\\\server\\share\\evil.pdf")) {
            assertThatThrownBy(() -> reader.read(new ByteArrayInputStream(zip(path)), ignored -> { }))
                    .as(path)
                    .isInstanceOf(ProjectArchiveSecurityException.class)
                    .hasMessageContaining("ABSOLUTE_PATH");
        }
    }

    private static byte[] zip(String... names) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name)); zip.write("safe".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
