import com.fuquan.audit.integration.material.OfficeDocumentTextReader;
import com.fuquan.audit.integration.ocr.fastapi.FastApiOcrAdapter;
import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxContainerGuard;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.review.application.port.*;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;
import java.nio.file.*;
import java.util.*;
import java.io.*;

/** Synthetic fixture coverage only. Prints counts/flags, never document text or configuration. */
public class OfficeCoverageProbe {
    public static void main(String[] args)throws Exception {
        var limits=new ProjectIntakeProperties(DataSize.ofMegabytes(500),DataSize.ofMegabytes(50),DataSize.ofMegabytes(500),200,10,100,1024);
        var guard=new ProjectDocxContainerGuard(new SelectedProjectArchiveReader(limits),limits);
        OcrPort ocr;
        if(args.length>1) {
            var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(java.time.Duration.ofSeconds(5));factory.setReadTimeout(java.time.Duration.ofSeconds(180));
            ocr=new FastApiOcrAdapter(RestClient.builder().baseUrl(args[1]).requestFactory(factory).build());
        } else ocr=request->new OcrResult(request.documentId(),"OCR_TEST_DOUBLE",1,List.of(new OcrPage(1,"OCR_TEST_DOUBLE",1)),false,"TEST_DOUBLE",1);
        var reader=new OfficeDocumentTextReader(guard::validate,ocr);
        boolean pass=true;int total=0;
        try(var files=Files.list(Path.of(args[0]))) {
            for(var file:files.sorted().toList()) {
                if(!file.getFileName().toString().startsWith("sample"))continue;
                long started=System.nanoTime();total++;
                try(var input=Files.newInputStream(file)) {
                    var result=reader.read(input,file.getFileName().toString(),UUID.randomUUID().toString(),500L*1024*1024,100,192000);
                    String text=String.join("",result.segments().stream().map(MaterialFieldAiPort.Segment::text).toList());
                    boolean marker=file.toString().endsWith(".xls")||file.toString().endsWith(".xlsx")?text.contains("31200")&&text.contains("excel")==false:text.contains("TAIL_OFFICE_COMPLETE");
                    boolean expect=!(args.length==1&&result.method().equals("OFFICE_TEXT_OCR")&&!file.toString().endsWith("mixed.pdf"));
                    System.out.printf(Locale.ROOT,"FIXTURE=%s; BYTES=%d; SEGMENTS=%d; CHARS=%d; TAIL=%s; METHOD=%s; SECONDS=%.2f%n",file.getFileName(),Files.size(file),result.segments().size(),text.length(),marker,result.method(),(System.nanoTime()-started)/1e9);
                    if(expect&&!marker)pass=false;
                } catch(Exception error) {
                    System.out.println("FIXTURE="+file.getFileName()+"; ERROR="+error.getClass().getSimpleName()+"; CODE="+(error instanceof DocumentTextPort.ReadFailure r?r.code():"UNKNOWN"));pass=false;
                    for(Throwable cause=error.getCause();cause!=null;cause=cause.getCause())if(cause.getMessage()!=null&&cause.getMessage().matches("[A-Z0-9_]+"))System.out.println("SAFE_CAUSE="+cause.getMessage());
                }
            }
        }
        System.out.println("SYNTHETIC_COVERAGE_FILES="+total+"; PASS="+pass);
        if(!pass)System.exit(1);
    }
}
