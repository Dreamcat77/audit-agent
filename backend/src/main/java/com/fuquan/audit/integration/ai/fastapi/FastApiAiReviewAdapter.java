package com.fuquan.audit.integration.ai.fastapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisPort;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisPort.BudgetSemanticAnalysisRequest;
import com.fuquan.audit.budget.application.ai.BudgetSemanticAnalysisResult;
import com.fuquan.audit.common.exception.ExternalServiceException;
import com.fuquan.audit.common.exception.ExternalServiceType;
import com.fuquan.audit.common.web.RequestIdFilter;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.ContractTextAnalysisRequest;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisFailure;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisFailure.Reason;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisResult;
import com.fuquan.audit.integration.http.FastApiContractPaths;
import com.fuquan.audit.procurement.application.port.ProcurementAgentReviewAiPort;
import com.fuquan.audit.procurement.application.port.ProcurementAgentReviewAiPort.ItemResult;
import com.fuquan.audit.procurement.application.port.ProcurementHeaderMappingAiPort;
import com.fuquan.audit.procurement.application.port.SpreadsheetHeaderMapping;
import com.fuquan.audit.procurement.application.port.ProcurementTenderReviewAiPort;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@Profile({"local-dev & local-ai", "dev29", "customer"})
public class FastApiAiReviewAdapter
        implements ContractTextAnalysisPort, com.fuquan.audit.contract.application.reviewrun.semantic.SemanticWorkPort,
        BudgetSemanticAnalysisPort, ProcurementAgentReviewAiPort,
        ProcurementHeaderMappingAiPort, ProcurementTenderReviewAiPort {

    private static final String CONTRACT_OPERATION = "CONTRACT_TEXT_ANALYSIS";
    private static final String BUDGET_OPERATION = "BUDGET_SEMANTIC_ANALYSIS";
    private static final String TENDER_OPERATION = "PROCUREMENT_TENDER_COMPLIANCE";
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();
    private static final Pattern SAFE_CODE = Pattern.compile("^[A-Z0-9_]{1,64}$");

    private final RestClient restClient;
    private final BoundedSemanticHttp semanticHttp=new BoundedSemanticHttp();

    public FastApiAiReviewAdapter(@Qualifier("aiRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override public com.fasterxml.jackson.databind.JsonNode plan(ContractTextAnalysisRequest request) {
        return semanticHttp.call(() -> semanticExchange("/ai/v1/agent/contract-semantic-work/plan",request),5_000);
    }

    @Override public com.fuquan.audit.contract.application.reviewrun.semantic.SemanticWorkPort.Response advance(
            ContractTextAnalysisRequest request,String planFingerprint,List<com.fasterxml.jackson.databind.JsonNode> checkpoints,
            String expectedOperationFingerprint,long remainingMs,boolean execute) {
        if (remainingMs<1 || remainingMs>3_600_000 || checkpoints.size()>64)
            throw new IllegalArgumentException("invalid semantic work bounds");
        var body=new java.util.LinkedHashMap<String,Object>();
        body.put("request",request);body.put("planFingerprint",planFingerprint);body.put("checkpoints",List.copyOf(checkpoints));
        body.put("expectedOperationFingerprint",expectedOperationFingerprint);body.put("remainingMs",remainingMs);body.put("execute",execute);
        var response=semanticHttp.call(() -> semanticExchange("/ai/v1/agent/contract-semantic-work/advance",body),
                execute?remainingMs+2_000:5_000);
        ContractTextAnalysisResult result=null;
        if (response.hasNonNull("result")) {
            try {
                var node=response.path("result").deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode)node).remove("available");
                // Existing audit rows are per actual unit. Never reinsert replayed
                // ModelCallData when the final Step publishes its findings.
                ((com.fasterxml.jackson.databind.node.ObjectNode)node).putArray("modelCalls");
                var mapper=ERROR_MAPPER.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
                result=mapContract(request,new ContractEnvelope(200,"success",mapper.treeToValue(node,ContractData.class)));
            } catch (java.io.IOException | RuntimeException invalid) {
                throw new IllegalStateException("invalid semantic final typed result");
            }
        }
        return new com.fuquan.audit.contract.application.reviewrun.semantic.SemanticWorkPort.Response(
                response.get("checkpoint"),response.get("next"),result,
                response.hasNonNull("failureCode")?response.path("failureCode").asText():null);
    }

    private com.fasterxml.jackson.databind.JsonNode semanticExchange(String path,Object body) {
        var outgoing=restClient.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body);
        propagateRequestId(outgoing);
        return outgoing.exchange((request,response) -> {
            // Read a fixed bounded buffer. No downstream URL/body is included
            // in exceptions, logs or public error messages.
            byte[] bytes=response.getBody().readNBytes(1_048_577);
            if (bytes.length>1_048_576 || response.getStatusCode().isError())
                throw new IllegalStateException("semantic work HTTP rejected or exceeded bound");
            try {
                var value=ERROR_MAPPER.readTree(bytes);
                if(value==null || !value.isObject()) throw new IllegalStateException("invalid semantic work envelope");
                return value;
            } catch(java.io.IOException invalid) { throw new IllegalStateException("invalid semantic work envelope"); }
        });
    }

    @Override
    public ContractTextAnalysisResult analyzeContractText(ContractTextAnalysisRequest request) {
        try {
            ContractEnvelope envelope = executeContract(request);
            return mapContract(request, envelope);
        } catch (ExternalServiceException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new ExternalServiceException(ExternalServiceType.AI, contractFailure(exception));
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new ExternalServiceException(ExternalServiceType.AI, exception);
        }
    }

    @Override
    public BudgetSemanticAnalysisResult analyzeBudgetSemantics(BudgetSemanticAnalysisRequest request) {
        try {
            BudgetEnvelope envelope = executeBudget(request);
            return mapBudget(request, envelope);
        } catch (ExternalServiceException exception) {
            throw exception;
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new ExternalServiceException(ExternalServiceType.AI, exception);
        }
    }

    /** Reuses the existing budget-semantic FastAPI operation for procurement explanations. */
    @Override
    public ProcurementAgentReviewAiPort.Result analyze(ProcurementAgentReviewAiPort.Request request) {
        try {
            BudgetSemanticAnalysisRequest budgetRequest = new BudgetSemanticAnalysisRequest(
                    request.projectId(), request.items().stream().map(item ->
                            new BudgetSemanticAnalysisPort.BudgetSemanticAnalysisItem(
                                    item.itemId(), procurementDescription(request, item),
                                    request.projectName(), analysisType(item), null, null,
                                    null, null, item.checkType(), exceptionData(request, item)))
                            .toList());
            BudgetSemanticAnalysisResult result = analyzeBudgetSemantics(budgetRequest);
            if (!result.available()) return ProcurementAgentReviewAiPort.Result.unavailable();

            Set<UUID> summaryIds = request.items().stream()
                    .filter(item -> "SUMMARY".equals(item.checkType()))
                    .map(ProcurementAgentReviewAiPort.Item::itemId)
                    .collect(java.util.stream.Collectors.toSet());
            List<ItemResult> mapped = result.items().stream()
                    .map(item -> {
                        String explanation = firstNonBlank(item.enhancedDescription(), item.hint(),
                                item.judgment(), item.suggestedCategory());
                        String suggestion = item.suggestion();
                        return explanation == null && suggestion == null ? null
                                : new ItemResult(item.itemId(), explanation, suggestion, item.needsConfirmation());
                    })
                    .filter(java.util.Objects::nonNull)
                    .toList();
            String summary = mapped.stream()
                    .filter(item -> summaryIds.contains(item.itemId()))
                    .map(item -> firstNonBlank(item.aiExplanation(), item.aiSuggestion()))
                    .findFirst().orElse(null);
            List<ItemResult> itemResults = mapped.stream()
                    .filter(item -> !summaryIds.contains(item.itemId()))
                    .toList();
            if (summary == null && itemResults.isEmpty()) throw invalidResponse();
            return new ProcurementAgentReviewAiPort.Result(true, result.model(), result.durationMs(),
                    summary, itemResults);
        } catch (ExternalServiceException exception) {
            throw exception;
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new ExternalServiceException(ExternalServiceType.AI, exception);
        }
    }

    @Override
    public ProcurementTenderReviewAiPort.Result analyze(ProcurementTenderReviewAiPort.Request request) {
        try {
            TenderComplianceEnvelope envelope = executeTenderCompliance(request);
            return mapTenderCompliance(request, envelope);
        } catch (ProcurementTenderReviewAiPort.TimeoutException
                | ProcurementTenderReviewAiPort.UnavailableException
                | ProcurementTenderReviewAiPort.InvalidResponseException exception) {
            throw exception;
        } catch (RestClientException exception) {
            if (exception.getMessage() != null
                    && exception.getMessage().toLowerCase(java.util.Locale.ROOT).contains("timeout")) {
                throw new ProcurementTenderReviewAiPort.TimeoutException(exception);
            }
            throw new ProcurementTenderReviewAiPort.UnavailableException(exception);
        } catch (IllegalArgumentException exception) {
            throw new ProcurementTenderReviewAiPort.InvalidResponseException(exception);
        }
    }

    @Override
    public ProcurementHeaderMappingAiPort.Result analyze(ProcurementHeaderMappingAiPort.Request request) {
        try {
            HeaderMappingEnvelope envelope = executeHeaderMapping(request);
            if (envelope == null || envelope.code() == null || envelope.code() != 200
                    || envelope.data() == null || !"PROCUREMENT_HEADER_MAPPING".equals(envelope.data().operation())
                    || envelope.data().mappings() == null || envelope.data().model() == null
                    || envelope.data().durationMs() == null) {
                throw invalidResponse();
            }
            List<SpreadsheetHeaderMapping> mappings = envelope.data().mappings().stream()
                    .map(item -> new SpreadsheetHeaderMapping(item.sourceHeader(), item.sourceColumnIndex(),
                            normalizeHeaderField(item.standardField()), item.confidence()))
                    .toList();
            return new ProcurementHeaderMappingAiPort.Result(true, envelope.data().model(),
                    envelope.data().durationMs(), envelope.data().headerRowNumber(), mappings);
        } catch (ExternalServiceException exception) {
            throw exception;
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new ExternalServiceException(ExternalServiceType.AI, exception);
        }
    }

    private static String analysisType(ProcurementAgentReviewAiPort.Item item) {
        if ("SUMMARY".equals(item.checkType())) return "PROCUREMENT_SUMMARY";
        return "PROCUREMENT_REVIEW_ITEM";
    }

    private static String procurementDescription(ProcurementAgentReviewAiPort.Request request,
                                                 ProcurementAgentReviewAiPort.Item item) {
        return "采购项目" + request.projectName() + "（项目编号" + request.projectCode()
                + "）的预审项“" + item.checkName() + "”（类型" + item.checkType()
                + "）：确定性校验状态为" + item.status() + "，规则风险等级为" + item.ruleRiskLevel()
                + "，规则详情为“" + item.detail() + "”，期望值为“"
                + String.valueOf(item.expectedValue()) + "”，实际值为“"
                + String.valueOf(item.actualValue()) + "”。请只解释这一项，必须引用实际值或明确说明实际值缺失，禁止使用泛化的“已由规则判定”“无需额外提示”等套话。";
    }

    private static String exceptionData(ProcurementAgentReviewAiPort.Request request,
                                        ProcurementAgentReviewAiPort.Item item) {
        String value = "projectId=" + request.projectId()
                + ";department=" + request.departmentName()
                + ";procurementType=" + request.procurementType()
                + ";procurementMethod=" + request.procurementMethod()
                + ";applicant=" + request.applicantName()
                + ";budgetAmount=" + request.budgetAmount()
                + ";expected=" + item.expectedValue()
                + ";actual=" + item.actualValue();
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String normalizeHeaderField(String field) {
        if (field == null) return null;
        return switch (field) {
            case "supplierName" -> "SUPPLIER_NAME";
            case "quoteAmount" -> "QUOTE_AMOUNT";
            case "productDescription" -> "PRODUCT_DESCRIPTION";
            case "budgetAmount" -> "BUDGET_AMOUNT";
            case "supplierCode" -> "SUPPLIER_CODE";
            case "procurementId" -> "PROCUREMENT_ID";
            default -> field;
        };
    }

    private ContractEnvelope executeContract(ContractTextAnalysisRequest request) {
        RestClient.RequestBodySpec httpRequest = restClient.post()
                .uri(FastApiContractPaths.CONTRACT_TEXT_ANALYSIS);
        propagateRequestId(httpRequest);
        return httpRequest.contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ContractEnvelope.class);
    }

    private BudgetEnvelope executeBudget(BudgetSemanticAnalysisRequest request) {
        RestClient.RequestBodySpec httpRequest = restClient.post()
                .uri(FastApiContractPaths.BUDGET_SEMANTIC_ANALYSIS);
        propagateRequestId(httpRequest);
        return httpRequest.contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(BudgetEnvelope.class);
    }

    private HeaderMappingEnvelope executeHeaderMapping(ProcurementHeaderMappingAiPort.Request request) {
        RestClient.RequestBodySpec httpRequest = restClient.post()
                .uri(FastApiContractPaths.PROCUREMENT_HEADER_MAPPING);
        propagateRequestId(httpRequest);
        return httpRequest.contentType(MediaType.APPLICATION_JSON)
                .body(new HeaderMappingRequestBody(request.sourceId(), request.format(),
                        request.allowedFields(), request.rows()))
                .retrieve()
                .body(HeaderMappingEnvelope.class);
    }

    private TenderComplianceEnvelope executeTenderCompliance(ProcurementTenderReviewAiPort.Request request) {
        RestClient.RequestBodySpec httpRequest = restClient.post()
                .uri(FastApiContractPaths.PROCUREMENT_TENDER_COMPLIANCE);
        propagateRequestId(httpRequest);
        return httpRequest.contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(TenderComplianceEnvelope.class);
    }

    private ProcurementTenderReviewAiPort.Result mapTenderCompliance(
            ProcurementTenderReviewAiPort.Request request, TenderComplianceEnvelope envelope) {
        if (envelope == null || envelope.code() == null || envelope.code() != 200
                || envelope.data() == null || !TENDER_OPERATION.equals(envelope.data().operation())
                || envelope.data().model() == null || envelope.data().durationMs() == null
                || envelope.data().findings() == null) {
            throw new ProcurementTenderReviewAiPort.InvalidResponseException(
                    new IllegalStateException("invalid tender compliance response envelope"));
        }
        Map<String, String> segmentTexts = request.segments().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ProcurementTenderReviewAiPort.Segment::sourceLocator,
                        ProcurementTenderReviewAiPort.Segment::text,
                        (first, ignored) -> first));
        List<ProcurementTenderReviewAiPort.Finding> findings = envelope.data().findings().stream()
                .map(item -> mapTenderFinding(item, segmentTexts))
                .toList();
        return new ProcurementTenderReviewAiPort.Result(envelope.data().model(), envelope.data().durationMs(), findings);
    }

    private ProcurementTenderReviewAiPort.Finding mapTenderFinding(
            TenderFindingData item, Map<String, String> segmentTexts) {
        if (item == null || item.sourceLocator() == null || !segmentTexts.containsKey(item.sourceLocator())
                || item.matchedText() == null || !segmentTexts.get(item.sourceLocator()).contains(item.matchedText())
                || item.pageNumber() != null && item.pageNumber() < 1
                || !isRiskLevel(item.riskLevel()) || item.confidence() == null
                || !Double.isFinite(item.confidence()) || item.confidence() < 0 || item.confidence() > 1) {
            throw new ProcurementTenderReviewAiPort.InvalidResponseException(
                    new IllegalStateException("invalid tender compliance finding"));
        }
        try {
            return new ProcurementTenderReviewAiPort.Finding(item.matchedText(), item.sourceLocator(),
                    item.pageNumber(), item.riskCategory(), item.riskLevel(), item.rationale(),
                    item.remediationSuggestion(), item.confidence());
        } catch (IllegalArgumentException exception) {
            throw new ProcurementTenderReviewAiPort.InvalidResponseException(exception);
        }
    }

    private static boolean isRiskLevel(String value) {
        return "CRITICAL".equals(value) || "WARNING".equals(value) || "INFO".equals(value);
    }

    private ContractTextAnalysisResult mapContract(
            ContractTextAnalysisRequest request, ContractEnvelope envelope) {
        if (envelope == null || envelope.code() == null || envelope.code() != 200
                || envelope.data() == null) {
            throw invalidResponse();
        }
        ContractData data = envelope.data();
        if (!CONTRACT_OPERATION.equals(data.operation()) || data.summaries() == null
                || data.hints() == null || data.model() == null || data.durationMs() == null
                || data.summaries().stream().anyMatch(value -> value == null || value.isBlank())) {
            throw invalidResponse();
        }
        Set<UUID> selectedFileIds = new HashSet<>();
        request.materials().forEach(material -> selectedFileIds.add(material.fileId()));
        List<ContractTextAnalysisResult.Hint> hints = data.hints().stream()
                .map(hint -> mapHint(hint, selectedFileIds))
                .toList();
        if ((data.monetaryObservation() != null
                && !request.semanticMoneyMode().equals(data.monetaryObservation().mode()))
                || ("OBSERVE".equals(request.semanticMoneyMode()) && data.monetaryObservation() == null))
            throw invalidResponse();
        return new ContractTextAnalysisResult(true, data.summaries(), hints,
                data.model(), data.durationMs(), mapModelCalls(data.modelCalls()), data.monetaryObservation());
    }

    private ContractTextAnalysisResult.Hint mapHint(
            ContractHintData hint, Set<UUID> selectedFileIds) {
        if (hint == null || hint.category() == null || hint.attention() == null
                || hint.title() == null || hint.description() == null
                || hint.evidenceRefs() == null
                || hint.evidenceRefs().stream().anyMatch(reference -> !selectedFileIds.contains(reference))) {
            throw invalidResponse();
        }
        List<ContractTextAnalysisResult.EvidenceItem> evidenceItems = hint.evidenceItems() == null
                ? List.of()
                : hint.evidenceItems().stream().map(item -> mapEvidenceItem(
                        item, hint.evidenceRefs(), selectedFileIds)).toList();
        return new ContractTextAnalysisResult.Hint(
                hint.category(), hint.attention(), hint.title(), hint.description(),
                hint.evidenceRefs(), evidenceItems);
    }

    private ContractTextAnalysisResult.EvidenceItem mapEvidenceItem(
            ContractEvidenceItemData item,
            List<UUID> evidenceRefs,
            Set<UUID> selectedFileIds) {
        if (item == null || item.fileId() == null || item.status() == null
                || !selectedFileIds.contains(item.fileId()) || !evidenceRefs.contains(item.fileId())) {
            throw invalidResponse();
        }
        String excerpt = item.excerpt() == null || item.excerpt().isBlank() ? null : item.excerpt().trim();
        return new ContractTextAnalysisResult.EvidenceItem(
                item.fileId(), item.status(), excerpt, false);
    }

    private BudgetSemanticAnalysisResult mapBudget(
            BudgetSemanticAnalysisRequest request, BudgetEnvelope envelope) {
        if (envelope == null || envelope.code() == null || envelope.code() != 200
                || envelope.data() == null) {
            throw invalidResponse();
        }
        BudgetData data = envelope.data();
        if (!BUDGET_OPERATION.equals(data.operation()) || data.items() == null
                || data.model() == null || data.durationMs() == null) {
            throw invalidResponse();
        }
        Set<UUID> requestedIds = new HashSet<>();
        request.items().forEach(item -> requestedIds.add(item.itemId()));
        List<BudgetSemanticAnalysisResult.Item> items = data.items().stream()
                .map(this::mapBudgetItem)
                .toList();
        if (items.stream().anyMatch(item -> !requestedIds.contains(item.itemId()))) {
            throw invalidResponse();
        }
        return new BudgetSemanticAnalysisResult(true, items, data.model(), data.durationMs());
    }

    private BudgetSemanticAnalysisResult.Item mapBudgetItem(BudgetItemData item) {
        if (item == null || item.itemId() == null
                || (isBlank(item.suggestedCategory())
                && isBlank(item.hint())
                && item.confidence() == null
                && isBlank(item.judgment())
                && item.similarityScore() == null
                && isBlank(item.enhancedDescription())
                && isBlank(item.suggestion()))) {
            throw invalidResponse();
        }
        return new BudgetSemanticAnalysisResult.Item(
                item.itemId(), item.suggestedCategory(), item.hint(),
                item.confidence(), item.judgment(), item.similarityScore(),
                item.enhancedDescription(), item.suggestion(), item.needsConfirmation());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ContractTextAnalysisFailure contractFailure(RestClientResponseException failure) {
        try {
            var root = ERROR_MAPPER.readTree(failure.getResponseBodyAsByteArray());
            String code = root.path("code").asText("");
            var reason = switch (code) {
                case "MODEL_INVALID_RESULT" -> Reason.MODEL_INVALID_RESULT;
                case "MODEL_GATEWAY_ERROR" -> Reason.MODEL_GATEWAY_ERROR;
                case "MODEL_TOTAL_BUDGET_EXHAUSTED" -> Reason.MODEL_TOTAL_BUDGET_EXHAUSTED;
                case "MODEL_NOT_CONFIGURED" -> Reason.MODEL_NOT_CONFIGURED;
                case "MODEL_INPUT_TOO_LARGE" -> Reason.MODEL_INPUT_TOO_LARGE;
                case "EMPTY_MODEL_INPUT" -> Reason.EMPTY_MODEL_INPUT;
                default -> Reason.SERVICE_UNAVAILABLE;
            };
            List<ModelCallData> calls = root.path("data").path("modelCalls").isArray()
                    ? ERROR_MAPPER.readerForListOf(ModelCallData.class)
                    .readValue(root.path("data").path("modelCalls")) : List.of();
            List<ContractTextAnalysisResult.ModelCall> mappedCalls = mapModelCalls(calls);
            if (reason == Reason.MODEL_GATEWAY_ERROR && mappedCalls.isEmpty()) {
                return new ContractTextAnalysisFailure(Reason.SERVICE_UNAVAILABLE);
            }
            return new ContractTextAnalysisFailure(reason, mappedCalls);
        } catch (RuntimeException | java.io.IOException invalidEnvelope) {
            return new ContractTextAnalysisFailure(Reason.SERVICE_UNAVAILABLE);
        }
    }

    private static List<ContractTextAnalysisResult.ModelCall> mapModelCalls(List<ModelCallData> values) {
        if (values == null) return List.of();
        Set<Integer> ordinals = new HashSet<>();
        return values.stream().map(value -> {
            if (value == null || value.ordinal() == null || value.ordinal() <= 0
                    || !ordinals.add(value.ordinal()) || value.phase() == null
                    || value.outcome() == null || value.durationMs() == null || value.durationMs() < 0
                    || value.provider() == null || value.provider().isBlank()
                    || value.model() == null || value.model().isBlank()
                    || (value.safeReason() != null && !SAFE_CODE.matcher(value.safeReason()).matches())
                    || negative(value.promptTokens()) || negative(value.completionTokens())
                    || negative(value.totalTokens())) throw new IllegalArgumentException("invalid model call audit");
            return new ContractTextAnalysisResult.ModelCall(value.ordinal(), value.phase(), value.outcome(),
                    value.safeReason(), value.durationMs(), value.finishReason(), value.promptTokens(),
                    value.completionTokens(), value.totalTokens(), value.provider(), value.model());
        }).toList();
    }

    private static boolean negative(Integer value) { return value != null && value < 0; }

    private ExternalServiceException invalidResponse() {
        return new ExternalServiceException(
                ExternalServiceType.AI,
                new IllegalStateException("invalid downstream response"));
    }

    private void propagateRequestId(RestClient.RequestHeadersSpec<?> request) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null && !requestId.isBlank()) {
            request.header(RequestIdFilter.HEADER_NAME, requestId);
        }
    }

    private record ContractEnvelope(Integer code, String message, ContractData data) {
    }

    private record ContractData(String operation, List<String> summaries,
                                List<ContractHintData> hints, String model, Long durationMs,
                                List<ModelCallData> modelCalls,
                                com.fuquan.audit.contract.application.ai.ContractMonetaryObservation monetaryObservation) {
    }

    private record ContractHintData(
            ContractTextAnalysisResult.SemanticCategory category,
            ContractTextAnalysisResult.Attention attention,
            String title,
            String description,
            List<UUID> evidenceRefs,
            List<ContractEvidenceItemData> evidenceItems) {
    }

    private record ContractEvidenceItemData(
            UUID fileId,
            ContractTextAnalysisResult.EvidenceStatus status,
            String excerpt) {
    }

    private record ModelCallData(Integer ordinal,
            ContractTextAnalysisResult.CallPhase phase,
            ContractTextAnalysisResult.CallOutcome outcome,
            String safeReason, Long durationMs, String finishReason,
            Integer promptTokens, Integer completionTokens, Integer totalTokens,
            String provider, String model) { }

    private record BudgetEnvelope(Integer code, String message, BudgetData data) {
    }

    private record BudgetData(String operation, List<BudgetItemData> items,
                              String model, Long durationMs) {
    }

    private record BudgetItemData(
            UUID itemId,
            String suggestedCategory,
            String hint,
            Double confidence,
            String judgment,
            Double similarityScore,
            String enhancedDescription,
            String suggestion,
            Boolean needsConfirmation) {
    }

    private record HeaderMappingRequestBody(String sourceId, String fileFormat,
                                            List<String> allowedFields,
                                            List<com.fuquan.audit.procurement.application.port.SpreadsheetHeaderPreview.PreviewRow> rows) {}

    private record HeaderMappingEnvelope(Integer code, String message, HeaderMappingData data) {}

    private record HeaderMappingData(String operation, Integer headerRowNumber,
                                     List<HeaderMappingDataItem> mappings, String model,
                                     Long durationMs) {}

    private record HeaderMappingDataItem(String sourceHeader, int sourceColumnIndex,
                                         String standardField, Double confidence) {}

    private record TenderComplianceEnvelope(Integer code, String message, TenderComplianceData data) {}

    private record TenderComplianceData(String operation, String model, Long durationMs,
                                        List<TenderFindingData> findings) {}

    private record TenderFindingData(String matchedText, String sourceLocator, Integer pageNumber,
                                     String riskCategory, String riskLevel, String rationale,
                                     String remediationSuggestion, Double confidence) {}
}
