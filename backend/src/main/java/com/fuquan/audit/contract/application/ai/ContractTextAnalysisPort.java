package com.fuquan.audit.contract.application.ai;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface ContractTextAnalysisPort {

    ContractTextAnalysisResult analyzeContractText(ContractTextAnalysisRequest request);

    record ContractTextAnalysisRequest(
            UUID projectId,
            List<ContractTextAnalysisMaterial> materials,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String semanticQualityProtocol,
            String semanticMoneyMode,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String semanticHierarchyProtocol) {

        public ContractTextAnalysisRequest(UUID projectId, List<ContractTextAnalysisMaterial> materials,
                String semanticQualityProtocol, String semanticMoneyMode) {
            this(projectId, materials, semanticQualityProtocol, semanticMoneyMode, null);
        }

        public ContractTextAnalysisRequest(UUID projectId, List<ContractTextAnalysisMaterial> materials,
                String semanticQualityProtocol) {
            this(projectId, materials, semanticQualityProtocol, "STRICT");
        }

        public ContractTextAnalysisRequest(UUID projectId, List<ContractTextAnalysisMaterial> materials) {
            this(projectId, materials, null);
        }

        public ContractTextAnalysisRequest {
            Objects.requireNonNull(projectId, "projectId must not be null");
            materials = List.copyOf(Objects.requireNonNull(materials, "materials must not be null"));
            semanticMoneyMode = ContractMonetaryObservation.requireMode(
                    semanticMoneyMode == null ? "STRICT" : semanticMoneyMode);
            if (materials.isEmpty()) {
                throw new IllegalArgumentException("materials must not be empty");
            }
            if (semanticQualityProtocol != null && !"contract-semantic-quality-v1".equals(semanticQualityProtocol)) {
                throw new IllegalArgumentException("unsupported semantic quality protocol");
            }
            if (semanticHierarchyProtocol != null && ((!"contract-bounded-hierarchy-v1".equals(semanticHierarchyProtocol)
                    && !"contract-bounded-hierarchy-v2".equals(semanticHierarchyProtocol))
                    || !"OBSERVE".equals(semanticMoneyMode))) {
                throw new IllegalArgumentException("unsupported semantic hierarchy protocol");
            }
        }
    }

    record ContractTextAnalysisMaterial(UUID fileId, String category, String text) {

        public ContractTextAnalysisMaterial {
            Objects.requireNonNull(fileId, "fileId must not be null");
            category = requireText(category, "category");
            text = requireText(text, "text");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
