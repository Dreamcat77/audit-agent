package com.fuquan.audit.integration.http;

public final class FastApiContractPaths {

    public static final String OCR_EXTRACT = "/ai/v1/ocr/extract";
    public static final String CONTRACT_TEXT_ANALYSIS = "/ai/v1/agent/contract-text-analysis";
    public static final String BUDGET_SEMANTIC_ANALYSIS = "/ai/v1/agent/budget-semantic-analysis";
    public static final String PROCUREMENT_HEADER_MAPPING = "/ai/v1/agent/procurement-header-mapping";
    public static final String PROCUREMENT_TENDER_COMPLIANCE =
            "/ai/v1/agent/procurement-tender-compliance";
    public static final String DOCUMENT_ROLE = "/ai/v1/agent/document-role";
    public static final String CONTRACT_FACT_CANDIDATES = "/ai/v1/agent/contract-fact-candidates";
    public static final String CONTRACT_FACT_CANDIDATES_V2 = "/ai/v2/agent/contract-fact-candidates";

    private FastApiContractPaths() {
    }
}
