package com.fuquan.audit.procurement.application;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.procurement.application.port.ProcurementQuoteCheckRepository;
import com.fuquan.audit.procurement.application.port.ProcurementProjectQueryPort;
import com.fuquan.audit.procurement.application.port.ProcurementQuoteSpreadsheetPort;
import com.fuquan.audit.procurement.domain.ProcurementQuoteCheck;
import com.fuquan.audit.procurement.domain.ProcurementProject;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
import com.fuquan.audit.procurement.application.port.SpreadsheetHeaderMapping;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.fuquan.audit.common.material.application.RepeatableMaterialInput;

/** F06 询比价异常分析用例。 */
@Service
public class ProcurementQuoteCheckService {

    private final ProcurementQuoteSpreadsheetPort spreadsheetPort;
    private final ProcurementQuoteCheckRepository repository;
    private final ProcurementProjectQueryPort projectQueryPort;
    private final ProcurementReviewWorkflowService workflowService;
    private final ProcurementQuoteCommitter committer;
    private final com.fuquan.audit.security.application.AuthContext auth;
    private final com.fuquan.audit.blacklist.application.port.BlacklistCheckPort blacklist;
    private final java.util.concurrent.Semaphore capacity = new java.util.concurrent.Semaphore(2);

    public ProcurementQuoteCheckService(ProcurementQuoteSpreadsheetPort spreadsheetPort,
                                         ProcurementQuoteCheckRepository repository,
                                         ProcurementProjectQueryPort projectQueryPort) {
        this(spreadsheetPort, repository, projectQueryPort, null);
    }

    public ProcurementQuoteCheckService(ProcurementQuoteSpreadsheetPort spreadsheetPort,
                                         ProcurementQuoteCheckRepository repository,
                                         ProcurementProjectQueryPort projectQueryPort,
                                         ProcurementReviewWorkflowService workflowService) {
        this(spreadsheetPort, repository, projectQueryPort, workflowService, null, null, null);
    }

    @Autowired
    public ProcurementQuoteCheckService(ProcurementQuoteSpreadsheetPort spreadsheetPort,
            ProcurementQuoteCheckRepository repository, ProcurementProjectQueryPort projectQueryPort,
            ProcurementReviewWorkflowService workflowService, ProcurementQuoteCommitter committer,
            com.fuquan.audit.security.application.AuthContext auth,
            com.fuquan.audit.blacklist.application.port.BlacklistCheckPort blacklist) {
        this.spreadsheetPort = spreadsheetPort;
        this.repository = repository;
        this.projectQueryPort = projectQueryPort;
        this.workflowService = workflowService;
        this.committer=committer; this.auth=auth; this.blacklist=blacklist;
    }

    public ProcurementQuoteCheck upload(String batchId, InputStream inputStream) throws IOException {
        return execute(batchId, inputStream, null, List.of());
    }

    public ProcurementQuoteCheck uploadConfirmed(String batchId, InputStream inputStream,
                                                 int headerRowNumber,
                                                 List<SpreadsheetHeaderMapping> mappings) throws IOException {
        return execute(batchId, inputStream, headerRowNumber, mappings);
    }

    private ProcurementQuoteCheck execute(String batchId, InputStream inputStream,
            Integer headerRowNumber, List<SpreadsheetHeaderMapping> mappings) throws IOException {
        if (!capacity.tryAcquire()) throw new BusinessException(BusinessErrorCode.CONFLICT, "询比价分析繁忙，请稍后重试同一文件");
        try (var source = RepeatableMaterialInput.copy(inputStream)) {
            var project = requireProject(batchId);
            var user = auth == null ? null : auth.currentUser().orElseThrow(() ->
                    new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "请先登录"));
            // Immutable content identity survives uncertain responses and refresh, including confirmed mappings.
            String configuration = "F06_COMPARISON_V2|" + project.id() + "|" + project.budgetAmount().stripTrailingZeros().toPlainString()
                    + "|" + (user == null ? "TEST" : user.userId()) + "|" + headerRowNumber + "|" + mappings;
            java.security.MessageDigest digest;
            try { digest=java.security.MessageDigest.getInstance("SHA-256"); }
            catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
            digest.update(configuration.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            try(var original=source.open()) { byte[] buffer=new byte[65536]; int count;
                while((count=original.read(buffer))!=-1) digest.update(buffer,0,count); }
            UUID identity = UUID.nameUUIDFromBytes(digest.digest());
            var existing = repository.findById(identity); if (existing.isPresent()) return existing.get();
            ProcurementQuoteSpreadsheetPort.ParsedSheet sheet;
            try(var stream=source.open()) {
                sheet = headerRowNumber == null ? spreadsheetPort.read(stream) : spreadsheetPort.read(stream, headerRowNumber, mappings);
            }
            var inputs = sheet.rows().stream().map(row -> new ProcurementQuoteCheck.QuoteInput(
                    row.rowNumber(),row.supplierName(),row.quoteAmount(),row.productDescription())).toList();
            java.util.Set<String> blocked = new java.util.HashSet<>();
            if (blacklist != null) blacklist.checkBatch(inputs.stream().map(q ->
                    new com.fuquan.audit.blacklist.application.port.BlacklistCheckPort.CheckCommand(
                            project.id().toString(),q.rowNumber(),project.id().toString(),q.supplierName(),null)).toList())
                    .stream().filter(o -> o.matched()).forEach(o -> blocked.add(o.supplierName()));
            var comparison = com.fuquan.audit.procurement.domain.ProcurementQuoteComparison.advise(
                    sheet.items().isEmpty() ? "SUPPLIER_ROWS" : "MATRIX", sheet.items().isEmpty() ? null : sheet.budgetAmount(),
                    project.budgetAmount(),sheet.conditions(),sheet.items(),inputs,blocked);
            ProcurementQuoteCheck result = ProcurementQuoteCheck.analyze(
                    project.id().toString(), project.budgetAmount(), inputs).withIdentityAndComparison(identity,comparison);
            try {
                if (committer != null) committer.commit(result,user);
                else {
                    repository.save(result);
                    if (workflowService != null) workflowService.recordQuoteCheck(project.id(), "已完成三方询比价分析");
                }
            } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
                // The losing transaction is rolled back before rediscovering the winner's committed result.
                return repository.findById(identity).orElseThrow(() -> duplicate);
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    exception.getMessage(), exception);
        } finally { capacity.release(); }
    }

    public ProcurementQuoteCheck requireByBatchId(String batchId) {
        return findByBatchId(batchId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "询比价分析批次不存在: " + batchId));
    }

    /** F06 grounded bid totals from already persisted F11 sources; identity uses canonical facts, not generated workbook bytes. */
    public ProcurementQuoteCheck analyzeSourceTotals(UUID projectId, List<com.fuquan.audit.procurement.domain.ProcurementCollusionDocument> documents) {
        var project = requireProject(projectId.toString());
        var user = auth == null ? null : auth.currentUser().orElseThrow(() -> new BusinessException(BusinessErrorCode.UNAUTHENTICATED, "请先登录"));
        var sorted = documents.stream().sorted(java.util.Comparator.comparing(d -> d.id().toString())).toList();
        var inputs = new java.util.ArrayList<ProcurementQuoteCheck.QuoteInput>();
        for (int i=0;i<sorted.size();i++) {
            var d=sorted.get(i);var amount=ProcurementBidQuotationExtractor.extract(d.parsedText());
            if(amount==null) throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,"投标文件缺少唯一明确的报价总额");
            inputs.add(new ProcurementQuoteCheck.QuoteInput(i+1,d.bidderName(),amount,"来源材料 " + d.id() + " / " + d.fileName()));
        }
        String canonical = "F06_BID_TOTALS_V1|" + projectId + "|" + project.budgetAmount().stripTrailingZeros().toPlainString()
                + "|" + (user==null ? "TEST" : user.userId()) + "|" + inputs;
        UUID identity=UUID.nameUUIDFromBytes(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var existing=repository.findById(identity); if(existing.isPresent()) return existing.get();
        var blocked=new java.util.HashSet<String>();
        if(blacklist!=null) blacklist.checkBatch(inputs.stream().map(q -> new com.fuquan.audit.blacklist.application.port.BlacklistCheckPort.CheckCommand(
                projectId.toString(),q.rowNumber(),projectId.toString(),q.supplierName(),null)).toList()).stream().filter(o->o.matched()).forEach(o->blocked.add(o.supplierName()));
        var comparison=com.fuquan.audit.procurement.domain.ProcurementQuoteComparison.advise("SUPPLIER_ROWS",null,project.budgetAmount(),List.of(),List.of(),inputs,blocked);
        var result=ProcurementQuoteCheck.analyze(projectId.toString(),project.budgetAmount(),inputs).withIdentityAndComparison(identity,comparison);
        try {
            if(committer!=null) committer.commit(result,user);
            else {repository.save(result);if(workflowService!=null)workflowService.recordQuoteCheck(projectId,"已完成投标总价分析");}
        } catch(org.springframework.dao.DataIntegrityViolationException ex) {return repository.findById(identity).orElseThrow(()->ex);}
        return result;
    }

    /** Source-grounded irregular quotations reuse the same deterministic comparison and durable commit. */
    public ProcurementQuoteCheck analyzeMaterialQuotes(UUID projectId, UUID materialId, String sha256,
            List<ProcurementQuoteCheck.QuoteInput> inputs) {
        return analyzeMaterialQuoteBatch(projectId, List.of(new MaterialQuoteSource(materialId, sha256)), inputs);
    }

    /** A single F06 result may be grounded in several immutable uploaded quotations. */
    public ProcurementQuoteCheck analyzeMaterialQuoteBatch(UUID projectId, List<MaterialQuoteSource> materials,
            List<ProcurementQuoteCheck.QuoteInput> inputs) {
        if(!capacity.tryAcquire()) throw new BusinessException(BusinessErrorCode.CONFLICT,"询比价分析繁忙，请稍后核对结果");
        try {
            var project=requireProject(projectId.toString());
            var user=auth==null ? null : auth.currentUser().orElseThrow(()->new BusinessException(BusinessErrorCode.UNAUTHENTICATED,"请先登录"));
            if(materials==null || materials.isEmpty() || materials.size()>200
                    || materials.stream().anyMatch(java.util.Objects::isNull)
                    || materials.stream().map(MaterialQuoteSource::materialId).distinct().count()!=materials.size()
                    || inputs==null || inputs.isEmpty() || inputs.size()>5000
                    || inputs.stream().map(q->q.supplierName().strip()).distinct().count()!=inputs.size())
                throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,"供应商报价缺失或重复，尚不能完成比价");
            var stableMaterials=materials.stream().sorted(java.util.Comparator.comparing(s->s.materialId().toString())).toList();
            String sourceIdentity=stableMaterials.stream().map(s->s.materialId()+":"+s.sha256()).collect(java.util.stream.Collectors.joining("|"));
            String canonical="F06_MATERIAL_QUOTES_V2|"+projectId+"|"+sourceIdentity+"|"
                    +project.budgetAmount().stripTrailingZeros().toPlainString()+"|"+(user==null?"TEST":user.userId())+"|"+inputs;
            UUID identity=UUID.nameUUIDFromBytes(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var existing=repository.findById(identity); if(existing.isPresent()) return existing.get();
            var blocked=new java.util.HashSet<String>();
            if(blacklist!=null) blacklist.checkBatch(inputs.stream().map(q->new com.fuquan.audit.blacklist.application.port.BlacklistCheckPort.CheckCommand(
                    projectId.toString(),q.rowNumber(),projectId.toString(),q.supplierName(),null)).toList())
                    .stream().filter(o->o.matched()).forEach(o->blocked.add(o.supplierName()));
            var comparison=com.fuquan.audit.procurement.domain.ProcurementQuoteComparison.advise("SUPPLIER_ROWS",null,
                    project.budgetAmount(),List.of(),List.of(),inputs,blocked);
            var result=ProcurementQuoteCheck.analyze(projectId.toString(),project.budgetAmount(),inputs).withIdentityAndComparison(identity,comparison);
            try {
                if(committer!=null) committer.commit(result,user);
                else { repository.save(result); if(workflowService!=null) workflowService.recordQuoteCheck(projectId,"已完成材料报价分析"); }
            } catch(org.springframework.dao.DataIntegrityViolationException duplicate) { return repository.findById(identity).orElseThrow(()->duplicate); }
            return result;
        } finally { capacity.release(); }
    }

    public record MaterialQuoteSource(UUID materialId, String sha256) {
        public MaterialQuoteSource {
            if (materialId == null || sha256 == null || !sha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid quotation source identity");
        }
    }

    /** 报告汇总使用：没有上传询比价材料时返回空，不改变 F06 查询接口语义。 */
    public Optional<ProcurementQuoteCheck> findByBatchId(String batchId) {
        return repository.findByBatchId(batchId);
    }

    /** F11 reads the same persisted F06 facts; no quotation data is copied into the scoring service. */
    public List<ProcurementQuoteCheckRepository.SupplierQuoteTimeline> findLatestSupplierQuotesBySubject(
            String subjectCode, Instant fiscalYearStart, Instant fiscalYearEnd) {
        return repository.findLatestSupplierQuotesBySubject(subjectCode, fiscalYearStart, fiscalYearEnd);
    }

    private ProcurementProject requireProject(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT, "采购项目ID不能为空");
        }
        final UUID projectId;
        try {
            projectId = UUID.fromString(batchId.trim());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(BusinessErrorCode.INVALID_ARGUMENT,
                    "采购项目ID格式不正确: " + batchId, exception);
        }
        var project = projectQueryPort.findById(projectId)
                .orElseThrow(() -> new BusinessException(BusinessErrorCode.NOT_FOUND,
                        "采购项目不存在: " + projectId));
        if (project.budgetAmount() == null) {
            throw new BusinessException(BusinessErrorCode.BUSINESS_VALIDATION_FAILED,
                    "采购项目未填写预算金额，无法进行询比价分析");
        }
        return project;
    }
}
