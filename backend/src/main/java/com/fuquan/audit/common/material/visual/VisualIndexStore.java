package com.fuquan.audit.common.material.visual;
import java.util.Optional;
public interface VisualIndexStore {
    void save(VisualIndex index);
    Optional<VisualIndex> find(String documentId,String fileSha256,String textSha256);
}
