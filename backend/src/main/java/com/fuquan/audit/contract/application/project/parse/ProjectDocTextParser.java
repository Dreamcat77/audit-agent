package com.fuquan.audit.contract.application.project.parse;

import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import com.fuquan.audit.contract.domain.project.ProjectFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.util.LittleEndian;
import org.springframework.stereotype.Component;

/** Read-only Word97 extraction; never converts or replaces the original stored resource. */
@Component
public class ProjectDocTextParser implements ProjectDocumentParser {
    public static final String CANONICAL_LAYOUT_VERSION = "DOC_BODY_ROW_V1";
    private static final byte[] MAGIC = {(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1};
    private static final int MAX_TEXT_CHARS = 2_000_000;
    private static final int MAX_PARAGRAPHS = 50_000;
    private final int maxBytes;

    public ProjectDocTextParser(ProjectIntakeProperties properties) {
        maxBytes = (int)Math.min(properties.maxUploadSize().toBytes(),8L*1024*1024);
    }
    @Override public boolean supports(ProjectFile file) {
        return file.originalFilename().toLowerCase(Locale.ROOT).endsWith(".doc");
    }
    @Override public ParsedProjectDocument parse(ProjectFile file, InputStream input) {
        try {
            byte[] bytes=bounded(input);
            byte[] response=ProjectDocProcess.parse(bytes);
            WorkerResult result=new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(response,WorkerResult.class);
            if(result==null)throw new ProjectParseException("DOC_WORKER_PROTOCOL_INVALID");
            if(result.error()!=null) {
                if(result.document()!=null || !result.error().matches("DOC_[A-Z_]{1,64}"))
                    throw new ProjectParseException("DOC_WORKER_PROTOCOL_INVALID");
                throw new ProjectParseException(result.error());
            }
            if(result.document()==null)throw new ProjectParseException("DOC_WORKER_PROTOCOL_INVALID");
            return result.document();
        } catch(IOException exception) { throw new ProjectParseException("DOC_WORKER_PROTOCOL_INVALID"); }
    }
    private byte[] bounded(InputStream input) throws IOException {
        byte[] bytes=input.readNBytes(maxBytes+1);
        if(bytes.length>maxBytes)throw new ProjectParseException("DOC_SIZE_LIMIT");
        if(bytes.length<8 || !Arrays.equals(MAGIC,Arrays.copyOf(bytes,8)))throw new ProjectParseException("DOC_MAGIC_MISMATCH");
        return bytes;
    }
    public record WorkerResult(ParsedProjectDocument document,String error) { }
    /** Binary stdin and bounded JSON stdout only; no document/error text is logged. */
    public static void main(String[] args) throws IOException {
        WorkerResult result;
        try {
            var parser=new ProjectDocTextParser(ProjectIntakeProperties.defaults());
            result=new WorkerResult(parser.parseInWorker(parser.bounded(System.in)),null);
        } catch(ProjectParseException exception) { result=new WorkerResult(null,exception.reasonCode()); }
        catch(RuntimeException | StackOverflowError exception) { result=new WorkerResult(null,"DOC_PARSE_FAILED"); }
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(System.out,result);
    }
    private ParsedProjectDocument parseInWorker(byte[] bytes) {
        long started = System.nanoTime();
        try {
            try(var fs=new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
                validateStreams(fs,bytes.length);
                try(var document=new HWPFDocument(fs)) {
                    if(document.getText().length()>MAX_TEXT_CHARS) throw new ProjectParseException("DOC_TEXT_LIMIT");
                    Range body=document.getRange();
                    int count=body.numParagraphs();
                    if(count>MAX_PARAGRAPHS) throw new ProjectParseException("DOC_STRUCTURE_LIMIT");
                    List<ParsedProjectDocument.Segment> segments=new ArrayList<>();
                    List<ParsedProjectDocument.Table> tables=new ArrayList<>();
                    int tableEnd=-1;
                    for(int p=0;p<count;p++) {
                        if(System.nanoTime()-started>10_000_000_000L) throw new ProjectParseException("DOC_TIME_LIMIT");
                        var paragraph=body.getParagraph(p);
                        if(paragraph.getStartOffset()<tableEnd) continue;
                        if(!paragraph.isInTable()) {
                            String text=text(paragraph.text());
                            if(!text.isBlank())segments.add(new ParsedProjectDocument.Segment("doc/body/paragraph:"+(p+1),text,null,p+1));
                            continue;
                        }
                        if(paragraph.getTableLevel()!=1)throw new ProjectParseException("DOC_NESTED_TABLE_UNSUPPORTED");
                        var table=body.getTable(paragraph);
                        tableEnd=table.getEndOffset();
                        String tableLocator="doc/table:"+(tables.size()+1);
                        if(table.numRows()>10_000)throw new ProjectParseException("DOC_STRUCTURE_LIMIT");
                        List<ParsedProjectDocument.Row> rows=new ArrayList<>();
                        for(int r=0;r<table.numRows();r++) {
                            var row=table.getRow(r);
                            if(row.numCells()>256)throw new ProjectParseException("DOC_STRUCTURE_LIMIT");
                            String rowLocator=tableLocator+"/row:"+(r+1);
                            List<ParsedProjectDocument.Cell> cells=new ArrayList<>();
                            for(int c=0;c<row.numCells();c++) {
                                var cell=row.getCell(c);
                                for(int n=0;n<cell.numParagraphs();n++)
                                    if(cell.getParagraph(n).getTableLevel()!=1)throw new ProjectParseException("DOC_NESTED_TABLE_UNSUPPORTED");
                                cells.add(new ParsedProjectDocument.Cell(rowLocator+"/cell:"+(c+1),text(cell.text())));
                            }
                            rows.add(new ParsedProjectDocument.Row(rowLocator,cells));
                            if(cells.stream().anyMatch(cell->!cell.text().isBlank())) {
                                String rowText=cells.stream().map(cell->cell.text().replace('\n','\u2028'))
                                        .collect(java.util.stream.Collectors.joining("\t"));
                                segments.add(new ParsedProjectDocument.Segment(rowLocator,rowText));
                            }
                        }
                        tables.add(new ParsedProjectDocument.Table(tableLocator,rows));
                    }
                    return new ParsedProjectDocument(segments,tables);
                }
            }
        } catch(ProjectParseException exception) { throw exception; }
        catch(IOException | RuntimeException exception) { throw new ProjectParseException("DOC_PARSE_FAILED",exception); }
    }
    private void validateStreams(POIFSFileSystem fs,int physicalBytes) throws IOException {
        var root=fs.getRoot();
        if(!root.hasEntry("WordDocument") || !root.getEntry("WordDocument").isDocumentEntry())
            throw new ProjectParseException("DOC_REQUIRED_STREAM_MISSING");
        long total=0;int count=0;
        for(var entry:root) {
            // Reject active content, embedded OLE objects and encrypted packages, including nested storages.
            String name=entry.getName().toLowerCase(Locale.ROOT);
            if(entry.isDirectoryEntry() || name.contains("vba") || name.contains("macro")
                    || name.contains("objectpool") || name.contains("encrypt"))
                throw new ProjectParseException("DOC_ACTIVE_CONTENT_UNSUPPORTED");
            int size=((DocumentEntry)entry).getSize();
            total+=size;
            if(++count>64 || size<0 || size>physicalBytes || total>physicalBytes)
                throw new ProjectParseException("DOC_STREAM_LIMIT");
        }
        byte[] fib;
        try(var word=root.createDocumentInputStream("WordDocument")) { fib=word.readNBytes(88); }
        if(fib.length<88 || LittleEndian.getUShort(fib,0)!=0xa5ec)
            throw new ProjectParseException("DOC_WORD_HEADER_INVALID");
        int version=LittleEndian.getUShort(fib,2), flags=LittleEndian.getUShort(fib,10);
        if(version<0xc1 || version>0x112)throw new ProjectParseException("DOC_WORD_VERSION_UNSUPPORTED");
        if((flags&0x8100)!=0)throw new ProjectParseException("DOC_ENCRYPTED_UNSUPPORTED");
        String table=(flags&0x0200)==0?"0Table":"1Table";
        if(!root.hasEntry(table) || !root.getEntry(table).isDocumentEntry())
            throw new ProjectParseException("DOC_REQUIRED_STREAM_MISSING");
        int chars=LittleEndian.getInt(fib,76);
        if(chars<0 || chars>MAX_TEXT_CHARS)throw new ProjectParseException("DOC_TEXT_LIMIT");
    }
    private static String text(String raw) {
        return Range.stripFields(raw).replace("\r\n","\n").replace('\r','\n').replace('\u000b','\n')
                .replace("\u0007","").replace("\u000c","").stripTrailing();
    }
}
