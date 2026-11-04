package com.fuquan.audit.contract.application.reviewrun.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.ContractTextAnalysisRequest;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisResult;
import java.util.List;

/** Private contract-only protocol. describe/plan MUST NOT perform inference. */
public interface SemanticWorkPort {
    JsonNode plan(ContractTextAnalysisRequest request);
    Response advance(ContractTextAnalysisRequest request, String planFingerprint,
            List<JsonNode> checkpoints, String expectedOperationFingerprint, long remainingMs, boolean execute);
    record Response(JsonNode checkpoint, JsonNode next, ContractTextAnalysisResult result, String failureCode) { }
}
