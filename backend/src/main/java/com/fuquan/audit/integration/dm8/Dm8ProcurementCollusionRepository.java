package com.fuquan.audit.integration.dm8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.procurement.application.port.ProcurementCollusionRepository;
import com.fuquan.audit.procurement.domain.ProcurementCollusionAssessment;
import com.fuquan.audit.procurement.domain.ProcurementCollusionDocument;
import com.fuquan.audit.procurement.domain.ProcurementSplitPurchaseAssessment;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Repository
@Profile({"dev29", "customer", "local-dev"})
public class Dm8ProcurementCollusionRepository implements ProcurementCollusionRepository {
    private static final Logger log = LoggerFactory.getLogger(Dm8ProcurementCollusionRepository.class);
    private final ProcurementCollusionMapper mapper; private final ObjectMapper objectMapper;
    public Dm8ProcurementCollusionRepository(ProcurementCollusionMapper mapper, ObjectMapper objectMapper) { this.mapper=mapper; this.objectMapper=objectMapper; }
    @Override @Transactional public void saveDocument(ProcurementCollusionDocument document) { mapper.insertDocument(documentRow(document)); }
    @Override @Transactional(readOnly = true) public List<ProcurementCollusionDocument> findDocuments(UUID projectId) { return mapper.findDocuments(projectId.toString()).stream().map(this::document).toList(); }
    @Override @Transactional public void saveAssessment(ProcurementCollusionAssessment assessment) { mapper.mergeAssessment(assessmentRow(assessment)); }
    @Override @Transactional(readOnly = true) public Optional<ProcurementCollusionAssessment> findLatestAssessment(UUID projectId) { ProcurementCollusionAssessmentPersistenceRow row=mapper.findAssessment(projectId.toString()); return row == null ? Optional.empty() : Optional.of(assessment(row)); }
    @Override @Transactional public void saveSplitPurchaseAssessment(ProcurementSplitPurchaseAssessment assessment) {
        ProcurementSplitPurchaseAssessmentPersistenceRow row = splitRow(assessment);
        log.info("F11 保存拆分采购识别结果: assessmentId={}, projectId=[{}], riskScore={}, riskLevel={}, "
                        + "findingCount={}, assessedTime={}",
                row.getId(), row.getProjectId(), row.getRiskScore(), row.getRiskLevel(), assessment.findings().size(),
                row.getAssessedTime());
        try {
            mapper.mergeSplitAssessment(row);
        } catch (RuntimeException exception) {
            log.error("F11 保存拆分采购识别结果失败: assessmentId={}, projectId=[{}]", row.getId(),
                    row.getProjectId(), exception);
            throw exception;
        }
    }
    @Override
    @Transactional(readOnly = true)
    public Optional<ProcurementSplitPurchaseAssessment> findLatestSplitPurchaseAssessment(UUID projectId) {
        ProcurementSplitPurchaseAssessmentPersistenceRow row = mapper.findSplitAssessment(projectId.toString());
        if (row == null) return Optional.empty();
        try {
            return Optional.of(splitAssessment(row));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            // A manually inserted or legacy placeholder with a non-UUID ID or {} finding payload is invalid.
            // Treat it as absent so the application service can rebuild and overwrite the snapshot.
            log.warn("F11 拆分采购识别结果格式无效，将重新生成: assessmentId={}, projectId=[{}], causeType={}",
                    row.getId(), row.getProjectId(), exception.getClass().getName());
            return Optional.empty();
        }
    }
    private static ProcurementCollusionDocumentPersistenceRow documentRow(ProcurementCollusionDocument v) { ProcurementCollusionDocumentPersistenceRow r=new ProcurementCollusionDocumentPersistenceRow(); r.setId(v.id().toString()); r.setProjectId(v.projectId().toString()); r.setBidderName(v.bidderName()); r.setIpAddress(v.ipAddress()); r.setHandlerName(v.handlerName()); r.setFileName(v.fileName()); r.setFileExtension(v.fileExtension()); r.setContentType(v.contentType()); r.setFileSize(v.fileSize()); r.setSha256(v.sha256()); r.setStorageKey(v.storageKey()); r.setParsedText(v.parsedText()); r.setCreatedTime(LocalDateTime.ofInstant(v.createdTime(), ZoneOffset.UTC)); return r; }
    private ProcurementCollusionDocument document(ProcurementCollusionDocumentPersistenceRow r) { return new ProcurementCollusionDocument(UUID.fromString(r.getId()),UUID.fromString(r.getProjectId()),r.getBidderName(),r.getIpAddress(),r.getHandlerName(),r.getFileName(),r.getFileExtension(),r.getContentType(),r.getFileSize(),r.getSha256(),r.getStorageKey(),r.getParsedText(),r.getCreatedTime().toInstant(ZoneOffset.UTC)); }
    private ProcurementCollusionAssessmentPersistenceRow assessmentRow(ProcurementCollusionAssessment v) { ProcurementCollusionAssessmentPersistenceRow r=new ProcurementCollusionAssessmentPersistenceRow(); r.setId(v.id().toString()); r.setProjectId(v.projectId().toString()); r.setRiskScore(v.riskScore()); r.setRiskLevel(v.riskLevel()); r.setQuoteCoefficientOfVariation(v.quoteCoefficientOfVariation()); r.setMaxDocumentSimilarity(v.maxDocumentSimilarity()); r.setDuplicateIpGroups(v.duplicateIpGroups()); r.setDuplicateHandlerGroups(v.duplicateHandlerGroups()); r.setSimilaritiesJson(json(v.similarities())); r.setAssessedTime(LocalDateTime.ofInstant(v.assessedTime(),ZoneOffset.UTC)); return r; }
    private ProcurementCollusionAssessment assessment(ProcurementCollusionAssessmentPersistenceRow r) { return new ProcurementCollusionAssessment(UUID.fromString(r.getId()),UUID.fromString(r.getProjectId()),r.getRiskScore(),r.getRiskLevel(),r.getQuoteCoefficientOfVariation(),r.getMaxDocumentSimilarity(),r.getDuplicateIpGroups(),r.getDuplicateHandlerGroups(),readPairs(r.getSimilaritiesJson()),r.getAssessedTime().toInstant(ZoneOffset.UTC)); }
    private ProcurementSplitPurchaseAssessmentPersistenceRow splitRow(ProcurementSplitPurchaseAssessment v) { ProcurementSplitPurchaseAssessmentPersistenceRow r=new ProcurementSplitPurchaseAssessmentPersistenceRow(); r.setId(v.id().toString()); r.setProjectId(v.projectId().toString()); r.setRiskScore(v.riskScore()); r.setRiskLevel(v.riskLevel()); r.setFindingsJson(json(v.findings())); r.setAssessedTime(LocalDateTime.ofInstant(v.assessedTime(),ZoneOffset.UTC)); return r; }
    private ProcurementSplitPurchaseAssessment splitAssessment(ProcurementSplitPurchaseAssessmentPersistenceRow r) { return new ProcurementSplitPurchaseAssessment(UUID.fromString(r.getId()),UUID.fromString(r.getProjectId()),r.getRiskLevel(),r.getRiskScore(),readSplitFindings(r.getFindingsJson()),r.getAssessedTime().toInstant(ZoneOffset.UTC)); }
    private String json(Object value) { try{return objectMapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("围串标评分明细序列化失败",e);} }
    private List<ProcurementCollusionAssessment.PairSimilarity> readPairs(String value) { try{return objectMapper.readValue(value,new TypeReference<List<ProcurementCollusionAssessment.PairSimilarity>>(){});}catch(JsonProcessingException e){throw new IllegalStateException("围串标评分明细格式无效",e);} }
    private List<ProcurementSplitPurchaseAssessment.Finding> readSplitFindings(String value) { try{return objectMapper.readValue(value,new TypeReference<List<ProcurementSplitPurchaseAssessment.Finding>>(){});}catch(JsonProcessingException e){throw new IllegalStateException("拆分采购识别明细格式无效",e);} }
}
