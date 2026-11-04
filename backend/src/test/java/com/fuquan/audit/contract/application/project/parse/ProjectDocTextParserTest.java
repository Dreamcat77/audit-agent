package com.fuquan.audit.contract.application.project.parse;

import com.fuquan.audit.contract.application.project.ProjectIntakeProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.util.unit.DataSize;
import static org.assertj.core.api.Assertions.*;

class ProjectDocTextParserTest {
    private ProjectDocumentParser parser(ProjectIntakeProperties properties) throws Exception {
        return new ProjectDocTextParser(properties);
    }
    private ParsedProjectDocument parse(byte[] bytes) throws Exception {
        return parser(ProjectIntakeProperties.defaults()).parse(
                ProjectDocumentParserTest.file("legacy.doc","application/msword"),new ByteArrayInputStream(bytes));
    }
    @Test void rejectsRenamedDocxRatherThanTreatingItAsLegacyWord() throws Exception {
        var parser=parser(ProjectIntakeProperties.defaults());
        assertThatThrownBy(()->parser.parse(ProjectDocumentParserTest.file("renamed.doc","application/msword"),
                new ByteArrayInputStream(new byte[]{'P','K',3,4})))
                .isInstanceOf(ProjectParseException.class).hasMessage("DOC_MAGIC_MISMATCH");
    }
    @Test void rejectsNonWordOleAndMalformedCompoundFiles() throws Exception {
        byte[] bytes;
        try(var fs=new POIFSFileSystem();var output=new ByteArrayOutputStream()) {
            fs.getRoot().createDocument("Workbook",new ByteArrayInputStream(new byte[32]));
            fs.writeFilesystem(output);bytes=output.toByteArray();
        }
        var parser=parser(ProjectIntakeProperties.defaults());
        byte[] nonWord=bytes;
        assertThatThrownBy(()->parser.parse(ProjectDocumentParserTest.file("excel.doc","application/msword"),new ByteArrayInputStream(nonWord)))
                .isInstanceOf(ProjectParseException.class).hasMessage("DOC_REQUIRED_STREAM_MISSING");
        assertThatThrownBy(()->parser.parse(ProjectDocumentParserTest.file("broken.doc","application/msword"),
                new ByteArrayInputStream(new byte[]{(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1})))
                .isInstanceOf(ProjectParseException.class).hasMessage("DOC_PARSE_FAILED");
    }
    @Test void stopsReadingAtConfiguredLimitEvenWhenMetadataClaimsSmallerSize() throws Exception {
        var parser=parser(new ProjectIntakeProperties(DataSize.ofBytes(16),null,null,0,0,0,0));
        assertThatThrownBy(()->parser.parse(ProjectDocumentParserTest.file("oversize.doc","application/msword"),new ByteArrayInputStream(new byte[17])))
                .isInstanceOf(ProjectParseException.class).hasMessage("DOC_SIZE_LIMIT");
    }
    @Test void rejectsEncryptedMacroAndMissingTableContainersBeforeHwpf() throws Exception {
        for(String mode:java.util.List.of("encrypted","macro","missing-table","text-limit")) {
            byte[] fib=new byte[88];
            org.apache.poi.util.LittleEndian.putShort(fib,0,(short)0xa5ec);
            org.apache.poi.util.LittleEndian.putShort(fib,2,(short)0xc1);
            if(mode.equals("encrypted"))org.apache.poi.util.LittleEndian.putShort(fib,10,(short)0x100);
            if(mode.equals("text-limit"))org.apache.poi.util.LittleEndian.putInt(fib,76,2_000_001);
            byte[] bytes;
            try(var fs=new POIFSFileSystem();var output=new ByteArrayOutputStream()) {
                fs.getRoot().createDocument("WordDocument",new ByteArrayInputStream(fib));
                if(!mode.equals("missing-table"))fs.getRoot().createDocument("0Table",new ByteArrayInputStream(new byte[32]));
                if(mode.equals("macro"))fs.getRoot().createDirectory("Macros");
                fs.writeFilesystem(output);bytes=output.toByteArray();
            }
            String reason=switch(mode) {
                case "encrypted"->"DOC_ENCRYPTED_UNSUPPORTED";
                case "macro"->"DOC_ACTIVE_CONTENT_UNSUPPORTED";
                case "text-limit"->"DOC_TEXT_LIMIT";
                default->"DOC_REQUIRED_STREAM_MISSING";
            };
            byte[] content=bytes;
            assertThatThrownBy(()->parse(content)).isInstanceOf(ProjectParseException.class).hasMessage(reason);
        }
    }
    @Test @EnabledIfSystemProperty(named="audit.word97.fixture",matches=".+")
    void genuineV2Word97RetainsBodyAndPhysicalRowsComparableWithDocx() throws Exception {
        Path folder=Path.of(System.getProperty("audit.word97.fixture"));
        byte[] bytes=Files.readAllBytes(folder.resolve("真实Word97_软件采购合同.doc"));
        var parsed=parse(bytes);
        var props=ProjectIntakeProperties.defaults();
        var docx=new ProjectDocxTextParser(new ProjectDocxContainerGuard(
                new com.fuquan.audit.contract.application.project.archive.SelectedProjectArchiveReader(props),props));
        ParsedProjectDocument reference;
        try(var input=Files.newInputStream(folder.resolve("等价内容_DOCX_正确上传路径.docx"))) {
            reference=docx.parse(ProjectDocumentParserTest.file("reference.docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document"),input);
        }
        // Comparing in the assertion avoids writing business text into reports on failure.
        assertThat(parsed.fullText().codePointCount(0,parsed.fullText().length())).isBetween(13000,14500);
        assertThat(parsed.tables()).isNotEmpty();
        assertThat(parsed.tables().stream().mapToInt(t->t.rows().size()).sum())
                .isEqualTo(reference.tables().stream().mapToInt(t->t.rows().size()).sum());
        assertThat(normalized(parsed.fullText()).equals(normalized(reference.fullText()))).isTrue();
        assertThat(parsed.segments()).allSatisfy(s->assertThat(s.sourceLocator()).startsWith("doc/"));
    }
    private String normalized(String text) { return text.replaceAll("\\s|\\u2028|\\u0007",""); }
}
