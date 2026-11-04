package com.fuquan.audit.procurement.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.common.observability.SafeExceptionDiagnostics;
import com.fuquan.audit.procurement.application.port.ProcurementAgentReviewSnapshotRepository;
import com.fuquan.audit.procurement.domain.ProcurementAgentReviewSnapshot;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 负责读取、生成并覆盖保存采购项目的最新智能体预审结果。 */
@Service
public class ProcurementAgentReviewSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(ProcurementAgentReviewSnapshotService.class);

    private final ProcurementProjectQueryService projectQueryService;
    private final ProcurementAgentReviewService reviewService;
    private final ProcurementAgentReviewSnapshotRepository snapshotRepository;
    private final ObjectMapper objectMapper;

    public ProcurementAgentReviewSnapshotService(
            ProcurementProjectQueryService projectQueryService,
            ProcurementAgentReviewService reviewService,
            ProcurementAgentReviewSnapshotRepository snapshotRepository,
            ObjectMapper objectMapper) {
        this.projectQueryService = projectQueryService;
        this.reviewService = reviewService;
        this.snapshotRepository = snapshotRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public Optional<ProcurementAgentReviewService.ReviewResult> find(UUID projectId) {
        projectQueryService.requireById(projectId);
        Optional<ProcurementAgentReviewSnapshot> snapshot = snapshotRepository.findByProjectId(projectId);
        log.info("采购预审快照查询 projectId={} result={}", projectId,
                snapshot.isPresent() ? "HIT" : "MISS");
        return snapshot.map(this::deserialize);
    }

    /** 返回当前项目页的预审摘要；底层 Repository 负责一次性批量读取。 */
    @Transactional(readOnly = true)
    public Map<UUID, ReviewSummary> findSummaries(Collection<UUID> projectIds) {
        if (projectIds == null || projectIds.isEmpty()) return Map.of();
        return snapshotRepository.findByProjectIds(projectIds).values().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ProcurementAgentReviewSnapshot::projectId, this::toSummary));
    }

    @Transactional
    public ProcurementAgentReviewService.ReviewResult refresh(UUID projectId) {
        projectQueryService.requireById(projectId);
        ProcurementAgentReviewService.ReviewResult result = reviewService.review(projectId);
        ProcurementAgentReviewSnapshot snapshot = new ProcurementAgentReviewSnapshot(
                UUID.randomUUID(), projectId, result.generatedTime(), serialize(result));
        log.info("准备保存采购预审快照 projectId={}", projectId);
        try {
            snapshotRepository.save(snapshot);
            log.info("采购预审快照已保存 projectId={} generatedTime={}", projectId, snapshot.generatedTime());
        } catch (RuntimeException exception) {
            var frame = SafeExceptionDiagnostics.safeTopStackFrame(exception);
            var sql = SafeExceptionDiagnostics.safeSqlDiagnostic(exception);
            log.error("event=PROCUREMENT_SNAPSHOT_SAVE_FAILED diagnosticSchemaVersion=1 "
                            + "module=PROCUREMENT layer=APPLICATION operation=saveSnapshot stage=DM8_COMPLETION "
                            + "outcome=FAILED reasonCode=PERSISTENCE_FAILURE projectId={} "
                            + "failureType={} rootType={} sqlState={} vendorCode={} "
                            + "stackClass={} stackMethod={} stackLine={}",
                    projectId, exception.getClass().getName(),
                    SafeExceptionDiagnostics.findRootCauseType(exception), sql.sqlState(), sql.vendorCode(),
                    frame.className(), frame.methodName(), frame.lineNumber());
            throw exception;
        }
        return result;
    }

    public void delete(UUID projectId) {
        snapshotRepository.deleteByProjectId(projectId);
    }

    private String serialize(ProcurementAgentReviewService.ReviewResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("采购预审结果序列化失败", exception);
        }
    }

    private ProcurementAgentReviewService.ReviewResult deserialize(ProcurementAgentReviewSnapshot snapshot) {
        try {
            return ProcurementAgentReviewService.referenceResult(objectMapper.readValue(
                    snapshot.reviewJson(), ProcurementAgentReviewService.ReviewResult.class));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "采购预审快照格式无效，请重新预审", exception);
        }
    }

    private ReviewSummary toSummary(ProcurementAgentReviewSnapshot snapshot) {
        ProcurementAgentReviewService.ReviewResult result = deserialize(snapshot);
        return new ReviewSummary("AVAILABLE", result.riskLevel(), snapshot.generatedTime());
    }

    public record ReviewSummary(String status, String riskLevel, Instant reviewTime) {}
}
