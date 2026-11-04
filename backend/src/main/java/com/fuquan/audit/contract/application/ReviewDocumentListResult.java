package com.fuquan.audit.contract.application;

import java.util.List;

public record ReviewDocumentListResult(
        List<ReviewDocumentView> currentMaterials,
        List<ReviewDocumentView> history) {

    public ReviewDocumentListResult {
        currentMaterials = List.copyOf(currentMaterials);
        history = List.copyOf(history);
    }
}
