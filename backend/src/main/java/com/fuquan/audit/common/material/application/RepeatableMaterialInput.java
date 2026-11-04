package com.fuquan.audit.common.material.application;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** Bounded disk spool; large uploads must not require an equally large heap buffer. */
public final class RepeatableMaterialInput implements AutoCloseable {
    public static final long MAX_BYTES = 500L * 1024 * 1024;
    private final Path path;
    private final String sha256;
    private RepeatableMaterialInput(Path path, String sha256) { this.path=path; this.sha256=sha256; }
    public static RepeatableMaterialInput copy(InputStream input) throws IOException {
        Path path=Files.createTempFile("audit-material-", ".source");
        try {
            var digest=MessageDigest.getInstance("SHA-256"); long size=0;
            try(var out=Files.newOutputStream(path)) {
                byte[] buffer=new byte[65536]; int count;
                while((count=input.read(buffer))!=-1) {
                    size+=count;
                    if(size>MAX_BYTES) throw new IOException("材料超过 500 MB 上传及读取上限");
                    out.write(buffer,0,count); digest.update(buffer,0,count);
                }
            }
            return new RepeatableMaterialInput(path,HexFormat.of().formatHex(digest.digest()));
        } catch(IOException|RuntimeException|NoSuchAlgorithmException failure) {
            Files.deleteIfExists(path);
            if(failure instanceof IOException io) throw io;
            throw new IOException("材料暂存失败",failure);
        }
    }
    public InputStream open() throws IOException { return Files.newInputStream(path); }
    public Path path() { return path; }
    public String sha256() { return sha256; }
    @Override public void close() throws IOException { Files.deleteIfExists(path); }
}
