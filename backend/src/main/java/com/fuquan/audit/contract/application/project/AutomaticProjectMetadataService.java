package com.fuquan.audit.contract.application.project;

import com.fuquan.audit.common.application.port.OperationAuditEvent;
import com.fuquan.audit.common.application.port.OperationAuditPort;
import com.fuquan.audit.common.application.port.OperationAuditResultStatus;
import com.fuquan.audit.contract.application.project.port.ExtractionResultRepository;
import com.fuquan.audit.contract.application.project.port.ProjectRepository;
import com.fuquan.audit.contract.domain.project.ExtractionResult;
import com.fuquan.audit.contract.domain.project.Project;
import com.fuquan.audit.security.domain.CurrentUser;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Establishes the intake project's metadata after parsing has produced durable facts.
 * This is a system action, not a user confirmation step. It never overwrites metadata
 * that was already established and records only the action, not extracted document text.
 */
@Service
public class AutomaticProjectMetadataService {
    static final String GENERATED_NAME_PREFIX = "自动整理项目-";
    private final ProjectRepository projects;
    private final ExtractionResultRepository extractions;
    private final OperationAuditPort audit;
    private final Clock clock;

    public AutomaticProjectMetadataService(ProjectRepository projects,
            ExtractionResultRepository extractions, OperationAuditPort audit, Clock clock) {
        this.projects = projects;
        this.extractions = extractions;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public boolean ensure(UUID projectId, CurrentUser actor, ProjectRequestMetadata metadata) {
        Project current = projects.findByIdForUpdate(projectId)
                .orElseThrow(() -> new IllegalStateException("project not found"));
        if (current.metadataConfirmed() && !isGeneratedName(current.name())) return false;
        var values=extractions.findByProjectId(projectId);
        String name = com.fuquan.audit.common.material.ProjectNameResolver.resolve(values.stream().filter(v->"PROJECT_NAME".equals(v.factType()))
                .map(v->v.normalizedValue()==null?v.valueText():v.normalizedValue()).filter(v->v!=null&&!v.isBlank())
                .toList(),values.stream().map(ExtractionResult::sourceText).toList(),null);
        String projectNo = uniqueFact(values, "PROJECT_NO");
        if(current.metadataConfirmed() && name==null)return false;
        if (name == null) name = GENERATED_NAME_PREFIX + projectId.toString().substring(0, 8);
        Project updated = current.confirmMetadata(current.versionNo(), name, projectNo, null, clock.instant());
        if (!projects.compareAndSet(current, updated)) return false;
        audit.append(new OperationAuditEvent("PROJECT_METADATA_AUTO_ESTABLISHED", "CONTRACT",
                "automaticProjectMetadata", actor.userId(), actor.username(), "PROJECT",
                projectId.toString(), null, OperationAuditResultStatus.SUCCESS, "SYSTEM_AUTO",
                "系统自动整理项目资料", "{" +
                "\"source\":\"PARSED_MATERIAL\",\"version\":\"v1\"}",
                metadata.requestId(), metadata.traceId(), metadata.sourceIp(), clock.instant()));
        return true;
    }

    private static String uniqueFact(List<ExtractionResult> values, String type) {
        List<String> candidates = values.stream()
                .filter(value -> type.equals(value.factType()))
                .map(value -> value.normalizedValue() == null ? value.valueText() : value.normalizedValue())
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().toList();
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    public static boolean isGeneratedName(String value) {
        return value != null && value.startsWith(GENERATED_NAME_PREFIX);
    }
}
