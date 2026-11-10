package com.fuquan.audit.common.material.visual;
import org.springframework.http.*;
public final class VisualPageResponse {
    private VisualPageResponse(){}
    public static ResponseEntity<byte[]> image(MaterialPageRenderer.Image image){
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).contentLength(image.png().length)
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff")
            .header("X-Page-Count",String.valueOf(image.pageCount())).body(image.png());
    }
}
