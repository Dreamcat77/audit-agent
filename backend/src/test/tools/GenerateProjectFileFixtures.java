import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/** Generates checked-in compatibility fixtures using only existing test dependencies. */
public final class GenerateProjectFileFixtures {
    private static final long FIXED_ZIP_TIME = 1_700_000_000_000L;

    private GenerateProjectFileFixtures() {
    }

    public static void main(String[] args) throws Exception {
        Path root = args.length == 0
                ? Path.of("src/test/resources/project-file-fixtures")
                : Path.of(args[0]);
        Files.createDirectories(root);

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("valid.pdf", pdf("Tender document compatibility fixture"));
        files.put("valid.docx", docx());
        files.put("valid-unix-0600.docx", permissionOnlyUnixZip(files.get("valid.docx")));
        files.put("valid.doc", legacyDoc());
        files.put("valid.xls", xls());
        files.put("valid.xlsx", xlsx());
        files.put("valid.png", image("png"));
        files.put("valid.jpg", image("jpg"));
        files.put("valid.jpeg", files.get("valid.jpg"));
        files.put("utf8-ascii.txt", "ordinary meeting notes".getBytes(StandardCharsets.UTF_8));
        files.put("utf8-chinese.txt", "补充材料：会议纪要。".getBytes(StandardCharsets.UTF_8));
        files.put("utf8-chinese-boundary.txt", "123456789012345会议纪要".getBytes(StandardCharsets.UTF_8));
        files.put("utf8-bom.txt", withUtf8Bom("带 BOM 的 UTF-8 文本"));
        files.put("nested.zip", zip(Map.of(
                "second-level/should-not-be-project-file.txt",
                "nested material".getBytes(StandardCharsets.UTF_8)), false));
        files.put("invalid-pdf-renamed.pdf", "MZ-not-a-pdf".getBytes(StandardCharsets.US_ASCII));
        files.put("invalid-docx-renamed.docx", "plain text, not OOXML".getBytes(StandardCharsets.UTF_8));
        files.put("invalid-xlsx-renamed.xlsx", "plain text, not OOXML".getBytes(StandardCharsets.UTF_8));
        files.put("binary-renamed.txt", new byte[]{0, 1, 2, 3, 'B', 'I', 'N'});
        files.put("invalid-image-renamed.png", files.get("nested.zip"));
        files.put("invalid-image-renamed.jpg", files.get("nested.zip"));
        files.put("invalid-image-renamed.jpeg", files.get("nested.zip"));
        files.put("malformed-docx-missing-parts.docx", zip(Map.of(
                "readme.txt", "not an OOXML document".getBytes(StandardCharsets.UTF_8)), true));

        files.put("project_file_format_matrix.zip", matrix(files));
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            Files.write(root.resolve(entry.getKey()), entry.getValue());
            System.out.println(entry.getKey() + " " + sha256(entry.getValue()));
        }
    }

    private static byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.getDocumentInformation().setTitle("Project file compatibility fixture");
            document.getDocumentInformation().setProducer("PDFBox fixture generator");
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(40, 700);
                content.showText(text);
                content.endText();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] docx() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            for (String line : new String[]{
                    "Contract No: FORMAT-001",
                    "Contract Name: Compatibility Contract",
                    "Party A: Fixture Buyer",
                    "Party B: Fixture Seller",
                    "Contract Amount: 125.50"}) {
                document.createParagraph().createRun().setText(line);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] permissionOnlyUnixZip(byte[] source) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipArchiveInputStream input = new ZipArchiveInputStream(new ByteArrayInputStream(source));
             ZipArchiveOutputStream archive = new ZipArchiveOutputStream(output)) {
            for (ZipArchiveEntry original; (original = input.getNextZipEntry()) != null; ) {
                ZipArchiveEntry entry = new ZipArchiveEntry(original.getName());
                entry.setTime(FIXED_ZIP_TIME);
                entry.setUnixMode(original.isDirectory() ? 0700 : 0600);
                archive.putArchiveEntry(entry);
                input.transferTo(archive);
                archive.closeArchiveEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] legacyDoc() throws Exception {
        try (POIFSFileSystem filesystem = new POIFSFileSystem()) {
            filesystem.getRoot().createDocument("WordDocument", new ByteArrayInputStream(
                    "Legacy Word storage compatibility fixture".getBytes(StandardCharsets.UTF_8)));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            filesystem.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    private static byte[] xls() throws Exception {
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            workbook.createSheet("Fixture").createRow(0).createCell(0)
                    .setCellValue("Legacy spreadsheet compatibility fixture");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] xlsx() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Fixture").createRow(0).createCell(0)
                    .setCellValue("OOXML spreadsheet compatibility fixture");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static byte[] image(String format) throws Exception {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.BLUE.getRGB());
        image.setRGB(1, 0, Color.WHITE.getRGB());
        image.setRGB(2, 0, Color.RED.getRGB());
        image.setRGB(0, 1, Color.GREEN.getRGB());
        image.setRGB(1, 1, Color.BLACK.getRGB());
        image.setRGB(2, 1, Color.YELLOW.getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) throw new IllegalStateException("image writer missing: " + format);
        return output.toByteArray();
    }

    private static byte[] matrix(Map<String, byte[]> files) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("01_合同/合同正文.docx", files.get("valid-unix-0600.docx"));
        entries.put("02_招标文件/招标文件.pdf", files.get("valid.pdf"));
        entries.put("03_投标文件/附件.doc", files.get("valid.doc"));
        entries.put("04_其他材料/报价表.xls", files.get("valid.xls"));
        entries.put("04_其他材料/明细表.xlsx", files.get("valid.xlsx"));
        entries.put("04_其他材料/现场照片.png", files.get("valid.png"));
        entries.put("04_其他材料/现场照片.jpg", files.get("valid.jpg"));
        entries.put("04_其他材料/现场照片.jpeg", files.get("valid.jpeg"));
        entries.put("04_其他材料/说明.txt", files.get("utf8-chinese-boundary.txt"));
        entries.put("04_其他材料/nested.zip", files.get("nested.zip"));
        return zip(entries, true);
    }

    private static byte[] zip(Map<String, byte[]> entries, boolean permissionOnlyUnixMode) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream archive = new ZipArchiveOutputStream(output)) {
            for (Map.Entry<String, byte[]> value : entries.entrySet()) {
                ZipArchiveEntry entry = new ZipArchiveEntry(value.getKey());
                entry.setTime(FIXED_ZIP_TIME);
                if (permissionOnlyUnixMode) entry.setUnixMode(0600);
                archive.putArchiveEntry(entry);
                archive.write(value.getValue());
                archive.closeArchiveEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] withUtf8Bom(String text) {
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[content.length + 3];
        result[0] = (byte) 0xef; result[1] = (byte) 0xbb; result[2] = (byte) 0xbf;
        System.arraycopy(content, 0, result, 3, content.length);
        return result;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
