package com.fuquan.audit.integration.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.review.application.port.OcrPort;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FieldSimulationReaderTest {
    @Test
    void frozenOfficeFilesUseTheNormalReaderWithoutOcrOrModelInput() throws Exception {
        Path directory = Path.of("..", "test-materials", "field-simulation-20261126-r3");
        var manifest = new ObjectMapper().readTree(directory.resolve("manifest.json").toFile());
        var ocr = mock(OcrPort.class);
        var reader = new OfficeDocumentTextReader(new MaterialParsingConfiguration().procurementDocxGuard(), ocr);
        int checked = 0;
        try {
            for (var batch : manifest.path("batches")) {
                for (var name : batch.path("files")) {
                    String filename = name.asText();
                    if (filename.endsWith(".pdf")) continue;
                    Path file = directory.resolve(batch.path("folder").asText()).resolve(filename);
                    try (var input = Files.newInputStream(file)) {
                        var result = reader.read(input, filename, "frozen-office-fixture", 10_000_000, 512, 1_000_000);
                        assertThat(result.sha256()).isEqualTo(batch.path("sha256").path(filename).asText());
                        assertThat(result.coverageGaps()).isEmpty();
                        assertThat(result.segments()).isNotEmpty();
                        assertThat(result.segments()).allSatisfy(segment -> {
                            assertThat(segment.locator()).isNotBlank();
                            assertThat(segment.text()).isNotBlank();
                        });
                        if (filename.equals("前滩设备巡检竞争性磋商文件.docx")) {
                            String text = result.segments().stream().map(segment -> segment.text())
                                    .collect(java.util.stream.Collectors.joining("\n"));
                            assertThat(text).contains("财政资金1800000元", "其他资金400000元");
                        }
                    }
                    checked++;
                }
            }
            assertThat(checked).isEqualTo(7);
            verifyNoInteractions(ocr);
        } finally {
            reader.close();
        }
    }
}
