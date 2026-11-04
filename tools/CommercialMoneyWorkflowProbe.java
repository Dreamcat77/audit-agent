import com.fuquan.audit.budget.application.*;
import com.fuquan.audit.common.material.application.*;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.integration.material.OfficeDocumentTextReader;
import com.fuquan.audit.integration.ai.fastapi.FastApiMaterialFieldAdapter;
import com.fuquan.audit.integration.ocr.fastapi.FastApiOcrAdapter;
import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader;
import com.fuquan.audit.contract.application.project.parse.ProjectDocxContainerGuard;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;

/** Real office/OCR/model/normalization probes with generated synthetic inputs only. No database writes. */
public class CommercialMoneyWorkflowProbe {
    static DocumentTextPort reader;
    static MaterialFieldAiPort ai;
    static int passed, failed;
    static void require(boolean condition) { if(!condition)throw new AssertionError("EXPECTED_SYNTHETIC_AMOUNT_MISMATCH"); }
    static void amount(BigDecimal value,String expected) { require(value!=null&&value.compareTo(new BigDecimal(expected))==0); }
    static DocumentTextPort.Result read(Path file)throws Exception {
        try(var in=Files.newInputStream(file)) {return reader.read(in,file.getFileName().toString(),UUID.randomUUID().toString(),500L*1024*1024,100,192000);}
    }
    static void budget(Path file,String declared,String approved)throws Exception {
        var doc=read(file);var id=UUID.randomUUID();
        var snapshot=new BudgetUploadedMaterialReader(reader,ai,null).extract(doc,id.toString());
        var result=new BudgetUploadedMaterialAnalyzer().analyze(List.of(new BudgetUploadedFacts.Input(id,"synthetic","PARSED",snapshot))).get(id);
        require(!result.lines().isEmpty());
        require(result.lines().stream().anyMatch(l->l.declaredAmount()!=null&&l.declaredAmount().compareTo(new BigDecimal(declared))==0
                &&(approved==null||l.approvalAmount()!=null&&l.approvalAmount().compareTo(new BigDecimal(approved))==0)));
        require(result.lines().stream().noneMatch(l->Set.of("CONFLICT","INVALID_AMOUNT").contains(l.status())));
    }
    static void procurement(Path file,String expected,boolean conflict)throws Exception {
        var doc=read(file);
        var result=ai.extract("PROCUREMENT",UUID.randomUUID().toString(),doc.sha256(),doc.segments());
        var fields=new MaterialFieldNormalizer().normalize(result,doc.segments().stream().map(s->new MaterialFieldNormalizer.Segment(s.locator(),s.text())).toList());
        if(conflict)require(fields.budgetAmount()==null&&fields.conflicts().contains("budgetAmount"));
        else amount(fields.budgetAmount(),expected);
    }
    interface Check {void run()throws Exception;}
    static void check(String name,Check check) {
        long started=System.nanoTime();
        try {check.run();passed++;System.out.println("SYNTHETIC_WORKFLOW case="+name+" pass=true seconds="+(System.nanoTime()-started)/1_000_000_000.0);}
        catch(Throwable error) {failed++;System.out.println("SYNTHETIC_WORKFLOW case="+name+" pass=false errorClass="+error.getClass().getSimpleName());}
    }
    public static void main(String[] args)throws Exception {
        var limits=new ProjectIntakeProperties(DataSize.ofMegabytes(500),DataSize.ofMegabytes(50),DataSize.ofMegabytes(500),200,10,100,1024);
        var guard=new ProjectDocxContainerGuard(new SelectedProjectArchiveReader(limits),limits);
        var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(java.time.Duration.ofSeconds(5));factory.setReadTimeout(java.time.Duration.ofSeconds(965));
        var http=RestClient.builder().baseUrl(args[0]).requestFactory(factory).build();
        String ocrBase=System.getenv("AUDIT_OCR_BASE_URL");
        var ocrHttp=ocrBase==null||ocrBase.isBlank()?http:RestClient.builder().baseUrl(ocrBase).requestFactory(factory).build();
        reader=new OfficeDocumentTextReader(guard::validate,new FastApiOcrAdapter(ocrHttp));
        ai=new FastApiMaterialFieldAdapter(http);
        if(args[1].endsWith(".png")) {
            Path scan=Path.of(args[1]);
            check("budget-uppercase-scan-ocr",()->budget(scan,"123456.78","100000"));
            check("procurement-uppercase-scan-ocr",()->procurement(scan,"123456.78",false));
            System.out.println("SYNTHETIC_UPPERCASE_SCAN_SUMMARY passed="+passed+" failed="+failed);
            if(failed>0)System.exit(1);return;
        }
        Path work=Path.of(args[1]);Files.createDirectories(work);
        String text="项目名称：仿真设备采购；项目编号：SYN-MONEY；科目名称：办公设备；预算金额：人民币壹拾贰万叁仟肆佰伍拾陆元柒角捌分；申报金额：人民币壹拾贰万叁仟肆佰伍拾陆元柒角捌分；批复参考金额：壹拾万元整。";
        // Keep terminal punctuation outside amount cells; the amount must be exact, never guessed.
        text=text.replace("元整。","元整");
        Path txt=work.resolve("uppercase.txt"),docx=work.resolve("uppercase.docx"),xlsx=work.resolve("sheet-unit.xlsx");
        Files.writeString(txt,text);
        try(var doc=new XWPFDocument();var out=Files.newOutputStream(docx)) {doc.createParagraph().createRun().setText(text);doc.write(out);}
        try(var book=new XSSFWorkbook();var out=Files.newOutputStream(xlsx)) {
            var sheet=book.createSheet("预算");sheet.createRow(0).createCell(0).setCellValue("单位：万元");
            String[] h={"项目编号","科目名称","本次申报金额","批复参考额度"},v={"SYN-UNIT","办公设备","12","10"};
            var header=sheet.createRow(1);var data=sheet.createRow(2);
            for(int i=0;i<h.length;i++){header.createCell(i).setCellValue(h[i]);data.createCell(i).setCellValue(v[i]);}book.write(out);
        }
        check("budget-uppercase-txt",()->budget(txt,"123456.78","100000"));
        check("budget-uppercase-docx",()->budget(docx,"123456.78","100000"));
        check("budget-xlsx-unit-and-alias",()->budget(xlsx,"120000","100000"));
        check("procurement-uppercase-txt",()->procurement(txt,"123456.78",false));
        check("procurement-uppercase-docx",()->procurement(docx,"123456.78",false));
        Path mismatch=work.resolve("mismatch.txt");Files.writeString(mismatch,"项目名称：仿真矛盾金额；预算金额：120000元（大写：壹拾万元整）");
        check("procurement-dual-mismatch-fail-closed",()->procurement(mismatch,null,true));
        Path narrative=work.resolve("narrative.txt");Files.writeString(narrative,"项目名称：仿真设备项目；科目名称：设备；设备本次申请9000元，经研究核准5000元。");
        check("budget-real-model-narrative-roles",()->budget(narrative,"9000","5000"));
        if(args.length>2) {
            Path fixtures=Path.of(args[2]);
            for(String name:List.of("sample.doc","sample.pdf","sample.png","sample.docx","sample.xlsx")) {
                Path file=fixtures.resolve(name);
                check("budget-office-"+name,()->budget(file,"31200",null));
                if(!name.endsWith("xlsx"))check("procurement-office-"+name,()->procurement(file,"200000",false));
            }
        }
        System.out.println("SYNTHETIC_WORKFLOW_SUMMARY passed="+passed+" failed="+failed+" database=NOT_USED httpUpload=NOT_TESTED");
        if(failed>0)System.exit(1);
    }
}
