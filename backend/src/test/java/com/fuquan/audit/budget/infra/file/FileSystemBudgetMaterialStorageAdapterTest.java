package com.fuquan.audit.budget.infra.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemBudgetMaterialStorageAdapterTest {
    @Test void timeoutCleanupIsIdempotentAndRejectsPathsOutsideItsRoot() throws Exception {
        var adapter=new FileSystemBudgetMaterialStorageAdapter(root);
        var timed=adapter.store(new ByteArrayInputStream(new byte[]{1}),"failed.xlsx",10);
        var successful=adapter.store(new ByteArrayInputStream(new byte[]{2}),"success.xlsx",10);
        adapter.delete(timed.reference());adapter.delete(timed.reference());
        assertThat(adapter.exists(timed.reference())).isFalse();assertThat(adapter.exists(successful.reference())).isTrue();
        assertThatThrownBy(()->adapter.delete("../success.xlsx")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->adapter.delete(root.toAbsolutePath().toString())).isInstanceOf(IllegalArgumentException.class);
    }

    @TempDir
    Path root;

    @Test
    void storesOpaqueReferenceAndCanReopenWithAnotherAdapter() throws Exception {
        FileSystemBudgetMaterialStorageAdapter first =
                new FileSystemBudgetMaterialStorageAdapter(root);
        FileSystemBudgetMaterialStorageAdapter second =
                new FileSystemBudgetMaterialStorageAdapter(root);

        var stored = first.store(
                new ByteArrayInputStream("budget".getBytes(StandardCharsets.UTF_8)),
                "预算材料.xlsx",
                1024);

        assertThat(stored.reference()).doesNotContain(root.toString());
        assertThat(stored.reference()).doesNotContain("\\");
        assertThat(stored.reference()).doesNotContain("/");
        try (InputStream reopened = second.open(stored.reference())) {
            assertThat(new String(reopened.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("budget");
        }
    }

    @Test
    void rejectsEmptyAndSizeLimitPlusOne() {
        FileSystemBudgetMaterialStorageAdapter adapter =
                new FileSystemBudgetMaterialStorageAdapter(root);

        assertThatThrownBy(() -> adapter.store(
                new ByteArrayInputStream(new byte[0]), "empty.xlsx", 10))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> adapter.store(
                new ByteArrayInputStream(new byte[11]), "too-large.xlsx", 10))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("size limit");
        assertThat(filesIn(root)).isZero();
    }

    @Test
    void acceptsOneByteAndExactLimit() throws Exception {
        FileSystemBudgetMaterialStorageAdapter adapter =
                new FileSystemBudgetMaterialStorageAdapter(root);

        var oneByte = adapter.store(
                new ByteArrayInputStream(new byte[] {1}), "one.xlsx", 1);
        var exact = adapter.store(
                new ByteArrayInputStream(new byte[] {1, 2, 3}), "exact.xlsx", 3);

        assertThat(adapter.exists(oneByte.reference())).isTrue();
        assertThat(adapter.exists(exact.reference())).isTrue();
        assertThat(filesIn(root)).isEqualTo(2);
    }

    @Test
    void stopsReadingOversizedStreamBeforeEndAndDeletesPartialFile() {
        FileSystemBudgetMaterialStorageAdapter adapter =
                new FileSystemBudgetMaterialStorageAdapter(root);
        CountingInputStream source = new CountingInputStream(new byte[1024 * 1024]);

        assertThatThrownBy(() -> adapter.store(source, "large.xlsx", 10 * 1024))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("size limit");

        assertThat(source.bytesRead()).isLessThan(source.length());
        assertThat(filesIn(root)).isZero();
    }

    @Test
    void rejectsAbsoluteAndTraversalReferences() throws Exception {
        FileSystemBudgetMaterialStorageAdapter adapter =
                new FileSystemBudgetMaterialStorageAdapter(root);

        for (String reference : Arrays.asList(
                "../evil.xlsx", "..\\evil.xlsx", "/root/evil.xlsx", "C:\\evil.xlsx")) {
            assertThat(adapter.exists(reference)).isFalse();
            assertThatThrownBy(() -> adapter.open(reference))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static long filesIn(Path root) {
        try (var files = Files.list(root)) {
            return files.count();
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class CountingInputStream extends InputStream {
        private final byte[] data;
        private int position;

        private CountingInputStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            if (position >= data.length) {
                return -1;
            }
            return data[position++] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position >= data.length) {
                return -1;
            }
            int count = Math.min(length, data.length - position);
            System.arraycopy(data, position, buffer, offset, count);
            position += count;
            return count;
        }

        private int length() {
            return data.length;
        }

        private int bytesRead() {
            return position;
        }
    }
}
