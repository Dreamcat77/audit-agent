package com.fuquan.audit.integration.material;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import org.apache.poi.poifs.filesystem.FileMagic;

/** Detect real Word containers, including common RTF/HTML exports carrying a .doc name. */
final class WordContainerFormat {
    private WordContainerFormat() {}
    static String detect(Path file,String declared)throws IOException{
        if(!declared.equals("doc")&&!declared.equals("docx"))return declared;
        byte[] head;try(var in=Files.newInputStream(file)){head=in.readNBytes(512);}
        if(head.length>=8&&FileMagic.valueOf(head)==FileMagic.OOXML)return "docx";
        String prefix=new String(head,StandardCharsets.ISO_8859_1).stripLeading().toLowerCase(Locale.ROOT);
        if(prefix.startsWith("{\\rtf"))return "rtf";
        if(prefix.matches("(?s)^(?:<!doctype\\s+html|<html|<head|<meta|<body).*"))return "html";
        return declared;
    }
}
