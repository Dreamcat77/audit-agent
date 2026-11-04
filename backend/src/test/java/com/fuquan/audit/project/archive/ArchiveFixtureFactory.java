package com.fuquan.audit.project.archive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Generates only ordinary standards-compliant ZIPs; edge metadata lives in fixed resources. */
final class ArchiveFixtureFactory {
    private ArchiveFixtureFactory() { }

    static byte[] ordinary(String name, byte[] content) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(content);
                zip.closeEntry();
            }
            return output.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("unable to create ordinary ZIP fixture", failure);
        }
    }
}
