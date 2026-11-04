import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.hslf.usermodel.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;

/** Generated synthetic upload fixtures, not customer documents. Run against the built JAR libraries. */
public class OfficeFormatFixtures {
    static final String[] IDENTITY={"项目名称：仿真办公设备更新项目","项目编号：SYN-OFFICE-2026","申请部门：采购管理部",
        "采购类型：货物","预算金额：200000元","预算科目编码：SYN-OFFICE","科目名称：办公设备",
        "采购方式：询价","申请人：仿真经办人","技术要求：设备应通过到货验收并保留验收记录。"};
    static final String LINE="科目名称：办公设备；申报金额：31200元；供应商名称：仿真办公供应公司；项目编号：SYN-OFFICE-2026";
    static final String TEXT=String.join("\n",IDENTITY)+"\n"+LINE+"\n测试尾标记：TAIL_OFFICE_COMPLETE";
    static Path output;
    public static void main(String[] args)throws Exception {
        output=Path.of(args[0]);Files.createDirectories(output);
        if(args.length==2&&args[1].equals("--table-only")) {
            try(var doc=new XWPFDocument();var stream=Files.newOutputStream(output.resolve("sample-table.docx"))) {
                for(String line:IDENTITY)doc.createParagraph().createRun().setText(line);
                var table=doc.createTable(9,5);
                String[] headers={"科目名称","申报金额","批复参考金额","供应商名称","项目编号"};
                for(int col=0;col<headers.length;col++)table.getRow(0).getCell(col).setText(headers[col]);
                for(int r=1;r<=8;r++) {
                    String[] values={"办公设备"+r,"31200","32760","仿真办公供应公司","SYN-OFFICE-2026"};
                    for(int col=0;col<values.length;col++)table.getRow(r).getCell(col).setText(values[col]);
                }
                doc.createParagraph().createRun().setText("TAIL_OFFICE_COMPLETE");doc.write(stream);
            }
            System.out.println("SYNTHETIC_WORD_TABLE_READY=8_ROWS");return;
        }
        try(var doc=new XWPFDocument();var stream=Files.newOutputStream(output.resolve("sample.docx"))) {
            for(String line:TEXT.split("\n"))doc.createParagraph().createRun().setText(line);
            doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createParagraph().createRun().setText("仿真测试资料");
            doc.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createParagraph().createRun().setText("FOOTER_COMPLETE");doc.write(stream);
        }
        for(var workbook:java.util.List.of(new XSSFWorkbook(),new HSSFWorkbook()))try(workbook;var stream=Files.newOutputStream(output.resolve(workbook instanceof XSSFWorkbook?"sample.xlsx":"sample.xls"))) {
            for(int s=0;s<2;s++) {
                var sheet=workbook.createSheet("明细"+(s+1));int r=0;
                for(String identity:IDENTITY)sheet.createRow(r++).createCell(0).setCellValue(identity);
                var header=sheet.createRow(r++);String[] headers={"科目名称","申报金额","批复参考金额","供应商名称","项目编号"};
                for(int c=0;c<headers.length;c++)header.createCell(c).setCellValue(headers[c]);
                var row=sheet.createRow(r);String[] values={"办公设备","31200","32760","仿真办公供应公司","SYN-OFFICE-2026"};
                for(int c=0;c<values.length;c++)row.createCell(c).setCellValue(values[c]);
            } workbook.write(stream);
        }
        try(var show=new XMLSlideShow();var stream=Files.newOutputStream(output.resolve("sample.pptx"))) {
            var slide=show.createSlide();var box=slide.createTextBox();box.setText(TEXT);show.write(stream);
        }
        try(var show=new HSLFSlideShow();var stream=Files.newOutputStream(output.resolve("sample.ppt"))) {
            var slide=show.createSlide();var box=new HSLFTextBox();box.setText(TEXT);slide.addShape(box);show.write(stream);
        }
        Files.writeString(output.resolve("sample.txt"),TEXT,StandardCharsets.UTF_8);
        Files.writeString(output.resolve("sample.csv"),TEXT,java.nio.charset.Charset.forName("GB18030"));
        Files.writeString(output.resolve("sample-utf16.txt"),TEXT,StandardCharsets.UTF_16);
        var rtf=new StringBuilder("{\\rtf1\\ansi ");
        for(char ch:TEXT.toCharArray()){if(ch=='\n')rtf.append("\\par ");else if(ch>127)rtf.append("\\u").append((int)(short)ch).append('?');else rtf.append(ch);}
        rtf.append('}');Files.writeString(output.resolve("sample.rtf"),rtf,StandardCharsets.US_ASCII);
        BufferedImage scan=new BufferedImage(1654,1800,BufferedImage.TYPE_INT_RGB);
        var graphics=scan.createGraphics();graphics.setColor(Color.WHITE);graphics.fillRect(0,0,scan.getWidth(),scan.getHeight());
        graphics.setColor(Color.BLACK);graphics.setFont(new Font("SimSun",Font.PLAIN,32));int y=80;
        for(String line:TEXT.split("\n")){graphics.drawString(line,60,y);y+=68;}graphics.dispose();
        for(String format:java.util.List.of("png","jpg","bmp"))ImageIO.write(scan,format,output.resolve("sample."+format).toFile());
        try(var stream=ImageIO.createImageOutputStream(output.resolve("sample.tiff").toFile())) {
            var writer=ImageIO.getImageWritersByFormatName("TIFF").next();writer.setOutput(stream);writer.prepareWriteSequence(null);
            for(int i=0;i<2;i++)writer.writeToSequence(new javax.imageio.IIOImage(scan,null,null),null);
            writer.endWriteSequence();writer.dispose();
        }
        for(boolean mixed:new boolean[]{false,true})try(var doc=new PDDocument();var stream=Files.newOutputStream(output.resolve(mixed?"sample-mixed.pdf":"sample.pdf"))) {
            var font=PDType0Font.load(doc,Path.of(args[1]).toFile());
            int pages=mixed?3:1;
            for(int i=0;i<pages;i++) {
                var page=new PDPage();doc.addPage(page);
                try(var content=new PDPageContentStream(doc,page)) {
                    if(i!=1){content.beginText();content.setFont(font,12);content.newLineAtOffset(35,760);for(String line:TEXT.split("\n")){content.showText(line);content.newLineAtOffset(0,-35);}content.endText();}
                    if(mixed&&i>0)content.drawImage(LosslessFactory.createFromImage(doc,scan),35,50,520,570);
                }
            } doc.save(stream);
        }
        // A physically large valid DOCX with a large Office thumbnail: source XML
        // remains short, so this checks upload bytes separately from model volume.
        Path large=output.resolve("sample-large.docx");
        try(var input=new java.util.zip.ZipInputStream(Files.newInputStream(output.resolve("sample.docx")));
            var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(large))) {
            for(var entry=input.getNextEntry();entry!=null;entry=input.getNextEntry()){
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                if(entry.getName().equals("[Content_Types].xml")) {
                    String xml=new String(input.readAllBytes(),StandardCharsets.UTF_8);
                    zip.write(xml.replace("</Types>","<Default Extension=\"bin\" ContentType=\"application/octet-stream\"/></Types>").getBytes(StandardCharsets.UTF_8));
                } else input.transferTo(zip);
                zip.closeEntry();
            }
            zip.putNextEntry(new java.util.zip.ZipEntry("docProps/synthetic-upload-padding.bin"));
            byte[] block=new byte[65536];var random=new Random(20261015);
            for(int i=0;i<400;i++){random.nextBytes(block);zip.write(block);}zip.closeEntry();
        }
        StringBuilder longText=new StringBuilder(TEXT).append('\n');
        for(int i=1;i<=120;i++)longText.append("第").append(i).append("条技术要求：设备应满足交付、验收及维护要求，保存相关记录供核对。\n");
        longText.append("技术要求：尾部设备应提供三年质保和完整移交清单。\n").append(LINE);
        Files.writeString(output.resolve("sample-long.txt"),longText,StandardCharsets.UTF_8);
        Files.writeString(output.resolve("expected-limit.txt"),TEXT+"正文".repeat(100000),StandardCharsets.UTF_8);
        Files.writeString(output.resolve("expected-corrupt.docx"),"not-a-word-file",StandardCharsets.UTF_8);
        System.out.println("SYNTHETIC_OFFICE_FIXTURES_READY");
    }
}
