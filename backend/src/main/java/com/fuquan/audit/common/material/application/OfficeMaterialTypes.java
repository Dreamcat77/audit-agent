package com.fuquan.audit.common.material.application;

import java.util.Locale;
import java.util.Map;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;

/** Shared upload contract; extensions never bypass content validation. Archives are excluded. */
public final class OfficeMaterialTypes {
    private OfficeMaterialTypes() {}
    private static final Map<String,String> TYPES = Map.ofEntries(
        Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        Map.entry("doc", "application/msword"),
        Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        Map.entry("xls", "application/vnd.ms-excel"),
        Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
        Map.entry("ppt", "application/vnd.ms-powerpoint"),
        Map.entry("pdf", "application/pdf"), Map.entry("txt", "text/plain"), Map.entry("csv", "text/csv"),
        Map.entry("rtf", "application/rtf"), Map.entry("png", "image/png"),
        Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("bmp", "image/bmp"),
        Map.entry("tif", "image/tiff"), Map.entry("tiff", "image/tiff"));
    public static String extension(String name) {
        return name == null ? "" : name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
    public static String mediaType(String name) { return TYPES.get(extension(name)); }
    public static boolean supports(String name) { return mediaType(name) != null; }
    public static boolean validSignature(String media, byte[] header) {
        if (media.startsWith("text/")) return header.length > 0 && !starts(header, new byte[]{80,75,3,4})
                && !starts(header, new byte[]{(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0});
        return switch (media) {
            case "application/pdf" -> starts(header, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case "image/png" -> starts(header, new byte[]{(byte)137,80,78,71,13,10,26,10});
            case "image/jpeg" -> starts(header, new byte[]{(byte)255,(byte)216,(byte)255});
            case "image/bmp" -> starts(header, new byte[]{66,77});
            case "image/tiff" -> starts(header,new byte[]{73,73,42,0}) || starts(header,new byte[]{77,77,0,42});
            case "application/msword", "application/vnd.ms-excel", "application/vnd.ms-powerpoint" ->
                starts(header,new byte[]{(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1});
            case "application/rtf" -> starts(header,"{\\rtf".getBytes(StandardCharsets.US_ASCII));
            default -> starts(header,new byte[]{80,75,3,4});
        };
    }
    private static boolean starts(byte[] bytes,byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(Arrays.copyOf(bytes,prefix.length),prefix);
    }
}
