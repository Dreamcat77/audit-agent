package com.fuquan.audit.common.material.infra;

import com.fuquan.audit.common.material.application.port.DocumentTextPort.ReadFailure;
import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import com.fuquan.audit.contract.application.project.archive.*;
import java.io.*;
import java.nio.file.*;
import java.util.HashSet;
import org.springframework.util.unit.DataSize;

/** Spreadsheet-specific resource policy; retains archive CRC, path and bomb protections. */
public final class SpreadsheetSafety {
    private SpreadsheetSafety() {}
    public static void verify(Path file) throws IOException {
        try(var header=Files.newInputStream(file)) {
            byte[] bytes=header.readNBytes(8);
            if(bytes.length>=4 && bytes[0]=='P' && bytes[1]=='K') {
                var limits=new ProjectIntakeProperties(DataSize.ofMegabytes(500),DataSize.ofMegabytes(500),
                        DataSize.ofMegabytes(1024),10000,20,100,1024);
                var names=new HashSet<String>();
                long[] xmlBytes={0};
                try(var input=Files.newInputStream(file)) {
                    new SelectedProjectArchiveReader(limits).read(input,entry -> {
                        names.add(entry.normalizedPath());
                        // XML is materialized by POI. Binary media/padding is streamed,
                        // so a large original does not itself exhaust parser memory.
                        if(entry.normalizedPath().endsWith(".xml") && entry.declaredSize()>64L*1024*1024)
                            throw new ProjectArchiveSecurityException("SPREADSHEET_XML_LIMIT");
                        if(entry.normalizedPath().endsWith(".xml")) {
                            xmlBytes[0]+=entry.declaredSize();
                            if(xmlBytes[0]>128L*1024*1024)throw new ProjectArchiveSecurityException("SPREADSHEET_XML_TOTAL_LIMIT");
                        }
                    });
                } catch(ProjectArchiveSecurityException failure) {
                    throw new ReadFailure("SPREADSHEET_"+failure.reasonCode(),failure);
                }
                if(!names.contains("xl/workbook.xml") || !names.contains("[Content_Types].xml"))
                    throw new ReadFailure("DOCUMENT_SIGNATURE_INVALID");
            } else if(bytes.length<8 || bytes[0]!=(byte)0xD0 || bytes[1]!=(byte)0xCF)
                throw new ReadFailure("DOCUMENT_SIGNATURE_INVALID");
        }
    }
}
