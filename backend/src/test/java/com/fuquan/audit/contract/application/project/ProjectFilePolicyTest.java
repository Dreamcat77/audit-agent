package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.contract.test.ProjectFileFixtures;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectFilePolicyTest {
    private final ProjectFilePolicy policy =
            new ProjectFilePolicy(ProjectIntakeProperties.defaults(), Duration.ofMinutes(15));

    @Test
    void directProductMatrixAllowsZipPdfDocxAndOcrImagesWithMatchingMimeAndMagic() {
        List<PolicyCase> allowed = List.of(
                new PolicyCase("intake.zip", "application/zip", "nested.zip"),
                new PolicyCase("document.pdf", "application/pdf", "valid.pdf"),
                new PolicyCase("document.doc", "application/msword", "valid.doc"),
                new PolicyCase("document.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "valid.docx"),
                new PolicyCase("photo.png", "image/png", "valid.png"),
                new PolicyCase("photo.jpg", "image/jpeg", "valid.jpg"),
                new PolicyCase("photo.jpeg", "image/jpeg", "valid.jpeg"));
        List<PolicyCase> rejected = List.of(
                new PolicyCase("sheet.xls", "application/vnd.ms-excel", "valid.xls"),
                new PolicyCase("sheet.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "valid.xlsx"),
                new PolicyCase("notes.txt", "text/plain", "utf8-ascii.txt"));

        allowed.forEach(value -> assertThat(decide(value, false))
                .as(value.filename()).isEqualTo(ProjectFilePolicy.Decision.DIRECT_UPLOAD_ALLOWED));
        rejected.forEach(value -> assertThat(decide(value, false))
                .as(value.filename()).isEqualTo(ProjectFilePolicy.Decision.REJECTED));
    }

    @Test void legacyWordAllowsKnownRealWordContainersAndRejectsTruncatedOle() {
        byte[] ole = {(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1};
        assertThat(policy.decide("contract.doc", "application/msword", false, 100, ole))
                .isEqualTo(ProjectFilePolicy.Decision.DIRECT_UPLOAD_ALLOWED);
        assertThat(policy.isParseSupported(com.fuquan.audit.contract.domain.project.ProjectFileCategory.CONTRACT_BODY,"contract.doc")).isTrue();
        for (boolean child : List.of(false,true)) {
            assertThat(policy.decide("renamed.doc","application/msword",child,100,new byte[]{'P','K',3,4}))
                    .isEqualTo(child?ProjectFilePolicy.Decision.ARCHIVE_CHILD_ALLOWED:ProjectFilePolicy.Decision.DIRECT_UPLOAD_ALLOWED);
            assertThat(policy.decide("truncated.doc","application/msword",child,100,Arrays.copyOf(ole,4)))
                    .isEqualTo(ProjectFilePolicy.Decision.REJECTED);
        }
    }

    @Test
    void archiveChildProductMatrixStoresEveryApprovedFormatWithMatchingMagic() {
        List<PolicyCase> allowed = List.of(
                new PolicyCase("document.pdf", "application/pdf", "valid.pdf"),
                new PolicyCase("document.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "valid.docx"),
                new PolicyCase("document.doc", "application/octet-stream", "valid.doc"),
                new PolicyCase("sheet.xls", "application/octet-stream", "valid.xls"),
                new PolicyCase("sheet.xlsx", "application/octet-stream", "valid.xlsx"),
                new PolicyCase("photo.png", "image/png", "valid.png"),
                new PolicyCase("photo.jpg", "image/jpeg", "valid.jpg"),
                new PolicyCase("photo.jpeg", "image/jpeg", "valid.jpeg"),
                new PolicyCase("notes.txt", "application/octet-stream", "utf8-chinese.txt"),
                new PolicyCase("nested.zip", "application/zip", "nested.zip"));

        allowed.forEach(value -> assertThat(decide(value, true))
                .as(value.filename()).isEqualTo(ProjectFilePolicy.Decision.ARCHIVE_CHILD_ALLOWED));
    }

    @Test
    void extensionMagicSpoofsAndHardRejectedExtensionsAreRejected() {
        List<PolicyCase> spoofs = List.of(
                new PolicyCase("evil.pdf", "application/pdf", "invalid-pdf-renamed.pdf"),
                new PolicyCase("evil.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "invalid-docx-renamed.docx"),
                new PolicyCase("evil.xlsx", "application/octet-stream", "invalid-xlsx-renamed.xlsx"),
                new PolicyCase("evil.png", "image/png", "invalid-image-renamed.png"),
                new PolicyCase("evil.jpg", "image/jpeg", "invalid-image-renamed.jpg"),
                new PolicyCase("evil.jpeg", "image/jpeg", "invalid-image-renamed.jpeg"),
                new PolicyCase("evil.txt", "application/octet-stream", "binary-renamed.txt"));
        spoofs.forEach(value -> assertThat(decide(value, true))
                .as(value.filename()).isEqualTo(ProjectFilePolicy.Decision.REJECTED));
        for (String extension : List.of("exe", "dll", "bat", "cmd", "ps1", "sh", "jar", "lnk",
                "js", "vbs", "com", "msi")) {
            byte[] pdf = ProjectFileFixtures.bytes("valid.pdf");
            assertThat(policy.decide("evil." + extension, "application/pdf", true, pdf.length, header(pdf)))
                    .as(extension).isEqualTo(ProjectFilePolicy.Decision.REJECTED);
        }
    }

    @Test
    void validChineseUtf8PrefixMayEndInsideThreeByteCharacter() {
        byte[] complete = "123456789012345会议纪要".getBytes(StandardCharsets.UTF_8);
        byte[] header = Arrays.copyOf(complete, 16);

        assertThat(header[15] & 0xff).isEqualTo(0xe4);
        assertThat(decide(header, complete.length)).isEqualTo(ProjectFilePolicy.Decision.ARCHIVE_CHILD_ALLOWED);
    }

    @Test
    void asciiUtf8PrefixIsAllowed() {
        byte[] value = "ordinary meeting notes".getBytes(StandardCharsets.UTF_8);

        assertThat(decide(value, value.length)).isEqualTo(ProjectFilePolicy.Decision.ARCHIVE_CHILD_ALLOWED);
    }

    @Test
    void utf8BomTextIsAllowedAsArchiveChild() {
        byte[] value = ProjectFileFixtures.bytes("utf8-bom.txt");

        assertThat(decide(header(value), value.length)).isEqualTo(ProjectFilePolicy.Decision.ARCHIVE_CHILD_ALLOWED);
    }

    @Test
    void malformedContinuationInMiddleIsRejected() {
        byte[] value = new byte[]{'o', 'k', (byte) 0xe4, 'x', '!'};

        assertThat(decide(value, value.length)).isEqualTo(ProjectFilePolicy.Decision.REJECTED);
    }

    @Test
    void overlongUtf8SequenceIsRejected() {
        byte[] value = new byte[]{'o', 'k', (byte) 0xc0, (byte) 0xaf};

        assertThat(decide(value, value.length)).isEqualTo(ProjectFilePolicy.Decision.REJECTED);
    }

    @Test
    void syntacticallyInvalidIncompleteSuffixIsRejected() {
        byte[] value = new byte[]{'o', 'k', (byte) 0xe0, (byte) 0x80};

        assertThat(decide(value, value.length)).isEqualTo(ProjectFilePolicy.Decision.REJECTED);
    }

    @Test
    void binaryControlBytesRenamedAsTextAreRejected() {
        byte[] value = new byte[]{0, 1, 2, 3, 'B', 'I', 'N'};

        assertThat(decide(value, value.length)).isEqualTo(ProjectFilePolicy.Decision.REJECTED);
    }

    private ProjectFilePolicy.Decision decide(byte[] header, long size) {
        return policy.decide("会议纪要.txt", "application/octet-stream", true, size, header);
    }

    private ProjectFilePolicy.Decision decide(PolicyCase value, boolean archiveChild) {
        byte[] bytes = ProjectFileFixtures.bytes(value.fixture());
        return policy.decide(value.filename(), value.mediaType(), archiveChild, bytes.length, header(bytes));
    }

    private static byte[] header(byte[] bytes) {
        return Arrays.copyOf(bytes, Math.min(16, bytes.length));
    }

    private record PolicyCase(String filename, String mediaType, String fixture) {
    }
}
