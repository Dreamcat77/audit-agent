package com.fuquan.audit.procurement.application;

import com.fuquan.audit.common.domain.RmbAmountParser;
import com.fuquan.audit.common.material.application.RepeatableMaterialInput;
import com.fuquan.audit.common.material.application.port.*;
import com.fuquan.audit.common.material.application.port.MaterialFieldAiPort.*;
import com.fuquan.audit.procurement.application.port.ProcurementQuoteSpreadsheetPort;
import com.fuquan.audit.procurement.domain.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Irregular spreadsheets remain usable materials; only proven vendor prices become quotation facts. */
@Service
public class ProcurementSpreadsheetMaterialService {
    private static final Pattern PRICE=Pattern.compile("报价|投标总价|响应总价|含税总价");
    private static final Pattern VENDOR=Pattern.compile("供应商|报价单位|投标人|投标单位|响应单位");
    private static final Set<String> FIELDS=Set.of("projectName","projectNo","departmentName","procurementType",
            "budgetAmount","subjectCode","subjectName","procurementMethod","requirementDescription","applicantName",
            "supplierName","quoteAmount","productDescription");
    private final DocumentTextPort reader;
    private final MaterialFieldAiPort ai;
    private final ProcurementQuoteSpreadsheetPort sheets;
    private final ProcurementQuoteCheckService quotes;
    private final ProcurementTenderDocumentService documents;
    private ProcurementMaterialTransactions transactions;
    @org.springframework.beans.factory.annotation.Autowired
    public void setTransactions(ProcurementMaterialTransactions transactions) { this.transactions=transactions; }
    public ProcurementSpreadsheetMaterialService(DocumentTextPort reader, MaterialFieldAiPort ai,
            ProcurementQuoteSpreadsheetPort sheets, ProcurementQuoteCheckService quotes, ProcurementTenderDocumentService documents) {
        this.reader=reader; this.ai=ai; this.sheets=sheets; this.quotes=quotes; this.documents=documents;
    }

    public ProcurementReviewMaterialService.Result upload(UUID projectId,String filename,String contentType,InputStream input,String actor) throws IOException {
        var source=documents.storeSpreadsheet(projectId,filename,contentType,input,actor,transactions);
        return analyze(projectId,source.id(),actor);
    }

    public ProcurementReviewMaterialService.Result analyze(UUID projectId, UUID sourceId, String actor) throws IOException {
        var source=documents.requireMaterial(projectId,sourceId);
        // Reopen proven success without model calls. Failed/unknown calls retain their durable ledger identity.
        if(Set.of("NON_QUOTATION","QUOTE_ANALYZED").contains(Objects.toString(source.parseEngine(),"")))
            return result(projectId,sourceId,source.parseEngine());
        documents.recordSpreadsheet(projectId,sourceId,"QUOTE_PROCESSING");
        try {
            DocumentTextPort.Result text;
            try(var input=documents.openMaterial(projectId,sourceId).inputStream()) {
                text=reader.read(input,source.fileName(),sourceId.toString(),RepeatableMaterialInput.MAX_BYTES,300,192000);
            }
            if(!source.sha256().equals(text.sha256())) throw new DocumentTextPort.ReadFailure("SOURCE_HASH_MISMATCH");
            // All worksheets have been read above. Only use the legacy one-sheet format
            // adapter when there really is one sheet, otherwise extract each physical row.
            long sheetCount=text.segments().stream().map(s->sheet(s.locator())).distinct().count();
            if(sheetCount==1) {
                com.fuquan.audit.procurement.application.port.SpreadsheetHeaderPreview preview;
                try(var input=documents.openMaterial(projectId,sourceId).inputStream()) { preview=sheets.preview(input,10); }
                var fields=preview.mappings().stream().map(m->m.standardField()).collect(java.util.stream.Collectors.toSet());
                if(fields.contains("QUOTE_MATRIX") || fields.containsAll(Set.of("SUPPLIER_NAME","QUOTE_AMOUNT"))) {
                    ProcurementQuoteCheck quote;
                    try(var input=documents.openMaterial(projectId,sourceId).inputStream()) { quote=quotes.upload(projectId.toString(),input); }
                    documents.recordSpreadsheet(projectId,sourceId,"QUOTE_ANALYZED",parsed(text,List.of()),null);
                    return new ProcurementReviewMaterialService.Result("QUOTE_ANALYZED","已识别报价信息并完成辅助分析",
                            documents.requireMaterial(projectId,sourceId),null,quote);
                }
            }
            var extraction=ai.extract("PROCUREMENT_REVIEW",sourceId.toString(),source.sha256(),contextual(text.segments()));
            var facts=ground(extraction,text.segments());
            var inputs=quotationInputs(facts,text.segments());
            boolean quoteLike=text.segments().stream().anyMatch(s->PRICE.matcher(s.text()).find() && VENDOR.matcher(s.text()).find());
            if(inputs.isEmpty() && quoteLike) throw new DocumentTextPort.ReadFailure("QUOTATION_FIELDS_INCOMPLETE");
            String status=inputs.isEmpty() ? "NON_QUOTATION" : "QUOTE_ANALYZED";
            ProcurementQuoteCheck quote=inputs.isEmpty() ? null : quotes.analyzeMaterialQuotes(projectId,sourceId,source.sha256(),inputs);
            documents.recordSpreadsheet(projectId,sourceId,status,parsed(text,facts),null);
            return new ProcurementReviewMaterialService.Result(status, message(status),documents.requireMaterial(projectId,sourceId),null,quote);
        } catch(MaterialExtractionUnknown unknown) {
            documents.recordSpreadsheet(projectId,sourceId,"QUOTE_UNKNOWN",null,"MODEL_OUTCOME_UNCONFIRMED");
            throw unknown;
        } catch(IOException failure) {
            String code=failure instanceof DocumentTextPort.ReadFailure typed ? typed.code() : "SPREADSHEET_READ_FAILED";
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("PROCUREMENT_SPREADSHEET projectId={} materialId={} stage=READ_OR_VALIDATION outcome=FAILED reasonCode={}",projectId,sourceId,code);
            documents.recordSpreadsheet(projectId,sourceId,"QUOTE_PARSE_FAILED",null,code);
            throw failure;
        } catch(com.fuquan.audit.common.exception.BusinessException failure) {
            documents.recordSpreadsheet(projectId,sourceId,"QUOTE_ANALYSIS_FAILED",null,"QUOTATION_ANALYSIS_FAILED");
            throw failure;
        } catch(RuntimeException failure) {
            documents.recordSpreadsheet(projectId,sourceId,"QUOTE_UNKNOWN",null,"MODEL_OR_STORAGE_UNCONFIRMED");
            throw failure;
        }
    }
    private ProcurementReviewMaterialService.Result result(UUID project,UUID source,String status) {
        return new ProcurementReviewMaterialService.Result(status,message(status),documents.requireMaterial(project,source),null,null);
    }
    static String message(String status) { return "NON_QUOTATION".equals(status)
            ? "材料已读取并完成信息提取；未发现可用于比价的供应商报价，本材料不参与比价"
            : "已识别报价信息并完成辅助分析"; }
    private static ProcurementTenderParseResult parsed(DocumentTextPort.Result text,List<Fact> facts) {
        return new ProcurementTenderParseResult(String.join("\n",text.segments().stream().map(Segment::text).toList()),
                text.segments().stream().map(s->new ProcurementTenderParseResult.Segment(s.locator(),s.text(),null,1.0)).toList(),facts);
    }
    private static String sheet(String locator) { int at=locator.indexOf("/row:"); return at<0 ? locator : locator.substring(0,at); }
    /** Repeat the nearest header as context; extracted excerpts must still occur in the actual row. */
    static List<Segment> contextual(List<Segment> segments) {
        var headers=new HashMap<String,Segment>(); var result=new ArrayList<Segment>();
        for(var segment:segments) {
            String key=sheet(segment.locator()); var header=headers.get(key);
            if(segment.text().contains(" | ") && (VENDOR.matcher(segment.text()).find() || segment.text().contains("金额"))) {
                // Data rows with amounts do not replace the header.
                if(Arrays.stream(segment.text().split(" \\| ",-1)).noneMatch(c->RmbAmountParser.parse(c).isPresent()))
                    headers.put(key,segment);
            }
            result.add(header==null || header==segment ? segment : new Segment(segment.locator(),
                    "表头上下文："+header.text()+"\n当前原文行："+segment.text()));
        }
        return List.copyOf(result);
    }
    static List<Fact> ground(Result extraction,List<Segment> segments) throws DocumentTextPort.ReadFailure {
        if(extraction==null || extraction.rows()==null) throw new DocumentTextPort.ReadFailure("MODEL_RESULT_INVALID");
        var facts=new LinkedHashSet<Fact>();
        for(var row:extraction.rows()) for(var fact:row.facts()) {
            var source=segments.stream().filter(s->s.locator().equals(fact.locator())).findFirst().orElse(null);
            if(!FIELDS.contains(fact.field()) || source==null || fact.value()==null || fact.value().isBlank()
                    || fact.excerpt()==null || !source.text().contains(fact.excerpt()) || !fact.excerpt().contains(fact.value()))
                throw new DocumentTextPort.ReadFailure("MODEL_EVIDENCE_INVALID");
            facts.add(fact);
        }
        return List.copyOf(facts);
    }
    static List<ProcurementQuoteCheck.QuoteInput> quotationInputs(List<Fact> facts,List<Segment> segments) throws DocumentTextPort.ReadFailure {
        var result=new ArrayList<ProcurementQuoteCheck.QuoteInput>();
        for(var segment:segments) {
            var prices=facts.stream().filter(f->f.locator().equals(segment.locator())&&f.field().equals("quoteAmount")).toList();
            var vendors=facts.stream().filter(f->f.locator().equals(segment.locator())&&f.field().equals("supplierName")).toList();
            if(prices.isEmpty()) continue;
            // Never join a vendor from another row/sheet or count a budget amount as an offer.
            if(vendors.size()!=1 || prices.size()!=1 || !roleProven(segment,prices.get(0),PRICE,segments)
                    || !roleProven(segment,vendors.get(0),VENDOR,segments)) throw new DocumentTextPort.ReadFailure("QUOTATION_ROLE_UNPROVEN");
            BigDecimal amount=RmbAmountParser.parse(prices.get(0).value()).orElseThrow(()->new DocumentTextPort.ReadFailure("QUOTATION_AMOUNT_INVALID"));
            String unit=columnUnit(segment,prices.get(0),segments);
            if(prices.get(0).value().matches("[+0-9,.，\\s]+")) {
                amount=RmbAmountParser.parse(prices.get(0).value()+unit).orElseThrow(()->new DocumentTextPort.ReadFailure("QUOTATION_AMOUNT_INVALID"));
            }
            var description=facts.stream().filter(f->f.locator().equals(segment.locator())&&f.field().equals("productDescription"))
                    .map(Fact::value).findFirst().orElse(null);
            result.add(new ProcurementQuoteCheck.QuoteInput(result.size()+1,vendors.get(0).value(),amount,description));
        }
        // A model may omit a row while returning valid JSON. Prove coverage of
        // each vendor/price row under an explicit quotation header before commit.
        String scope=null; String[] header=null;
        for(var segment:segments) {
            if(!sheet(segment.locator()).equals(scope)) { scope=sheet(segment.locator()); header=null; }
            String[] cells=segment.text().split(" \\| ",-1);
            if(Arrays.stream(cells).anyMatch(c->PRICE.matcher(c).find())
                    && Arrays.stream(cells).anyMatch(c->VENDOR.matcher(c).find())
                    && Arrays.stream(cells).noneMatch(c->RmbAmountParser.parse(c).isPresent())) { header=cells; continue; }
            if(header==null)continue;
            for(int column=0;column<Math.min(header.length,cells.length);column++) {
                if(PRICE.matcher(header[column]).find() && !cells[column].isBlank()
                        && facts.stream().noneMatch(f->f.locator().equals(segment.locator())&&f.field().equals("quoteAmount")))
                    throw new DocumentTextPort.ReadFailure("QUOTATION_FIELDS_INCOMPLETE");
            }
        }
        return List.copyOf(result);
    }
    private static boolean roleProven(Segment source,Fact fact,Pattern role,List<Segment> segments) {
        String[] cells=source.text().split(" \\| ",-1);
        for(int column=0;column<cells.length;column++) {
            if(!cells[column].contains(fact.value())) continue;
            if(role.matcher(cells[column]).find()) return true;
            for(int i=segments.indexOf(source)-1;i>=0;i--) {
                var preceding=segments.get(i);
                if(!sheet(source.locator()).equals(sheet(preceding.locator()))) break;
                String[] header=preceding.text().split(" \\| ",-1);
                if(Arrays.stream(header).anyMatch(c->PRICE.matcher(c).find()||VENDOR.matcher(c).find()))
                    return column<header.length && role.matcher(header[column]).find();
            }
        }
        return false;
    }
    private static String columnUnit(Segment source,Fact fact,List<Segment> segments) {
        String[] cells=source.text().split(" \\| ",-1);
        for(int column=0;column<cells.length;column++)if(cells[column].contains(fact.value()))
            for(int i=segments.indexOf(source)-1;i>=0;i--) {
                var preceding=segments.get(i);if(!sheet(source.locator()).equals(sheet(preceding.locator())))break;
                String[] headers=preceding.text().split(" \\| ",-1);
                if(column<headers.length && PRICE.matcher(headers[column]).find()) {
                    String header=headers[column];
                    return header.contains("亿元")?"亿元":header.contains("万元")?"万元":header.contains("千元")?"千元":"元";
                }
            }
        return "元";
    }
}
