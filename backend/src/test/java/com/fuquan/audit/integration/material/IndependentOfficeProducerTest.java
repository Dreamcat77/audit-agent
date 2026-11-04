package com.fuquan.audit.integration.material;
import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.common.material.application.port.*;
import java.io.*;
import org.junit.jupiter.api.Test;
class IndependentOfficeProducerTest {
    @Test void spreadsheetProducedByOpenpyxlRetainsChineseBudgetAndApproval() throws Exception {
        var reader=new OfficeDocumentTextReader(in->{try{return in.readAllBytes();}catch(IOException e){throw new UncheckedIOException(e);}},request->{throw new AssertionError("Native XLSX must not call OCR");});
        try(var in=getClass().getResourceAsStream("/material-v15/预算表.xlsx")) {
            var result=reader.read(in,"budget.xlsx","test",1000000,100,192000);
            assertThat(result.segments()).anyMatch(s->s.text().contains("120000")&&s.text().contains("110000"));
        }
    }
    @Test void closingSpreadsheetDoesNotRequireSharedPoiWriteDirectory() throws Exception {
        var strategy=new org.apache.poi.util.TempFileCreationStrategy() {
            public File createTempFile(String prefix,String suffix) throws IOException {throw new IOException("Shared POI directory not writable");}
            public File createTempDirectory(String prefix) throws IOException {throw new IOException("Shared POI directory not writable");}
        };
        org.apache.poi.util.TempFile.setTempFileCreationStrategy(strategy);
        try {spreadsheetProducedByOpenpyxlRetainsChineseBudgetAndApproval();}
        finally {org.apache.poi.util.TempFile.setTempFileCreationStrategy(new org.apache.poi.util.DefaultTempFileCreationStrategy());}
    }
}
