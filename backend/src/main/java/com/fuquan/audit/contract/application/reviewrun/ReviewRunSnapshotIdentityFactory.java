package com.fuquan.audit.contract.application.reviewrun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRunMaterial;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Canonical input manifest identity, excluding generated run/row IDs and clock time. */
@Component
public class ReviewRunSnapshotIdentityFactory {
    private static final ObjectMapper JSON = new ObjectMapper();

    public String identity(UUID projectId, UUID contractId, long contractVersion, String contractSnapshotSha256,
            UUID sourceCandidateId, UUID sourceConfirmationId, List<ContractReviewRunMaterial> materials,
            String reviewProfileVersion, String ruleVersion, String modelPolicyVersion) {
        return identity(ContractReviewProfileVersions.RUN_SCHEMA_VERSION, projectId, contractId,
                contractVersion, contractSnapshotSha256,
                sourceCandidateId, sourceConfirmationId, materials, reviewProfileVersion, ruleVersion,
                modelPolicyVersion);
    }

    public String identity(int runSchemaVersion, UUID projectId, UUID contractId, long contractVersion,
            String contractSnapshotSha256, UUID sourceCandidateId, UUID sourceConfirmationId,
            List<ContractReviewRunMaterial> materials, String reviewProfileVersion, String ruleVersion,
            String modelPolicyVersion) {
        if (runSchemaVersion <= 0) throw new IllegalArgumentException("invalid run schema version");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("runSchemaVersion", runSchemaVersion);
        fields.put("projectId", projectId.toString());
        fields.put("contractId", contractId.toString());
        fields.put("contractVersion", contractVersion);
        fields.put("contractSnapshotSha256", contractSnapshotSha256);
        fields.put("sourceCandidateIdentity", sourceCandidateId == null
                ? "LEGACY_NO_CANDIDATE" : sourceCandidateId.toString());
        fields.put("sourceConfirmationIdentity", sourceConfirmationId == null
                ? "LEGACY_NO_CONFIRMATION" : sourceConfirmationId.toString());
        fields.put("reviewProfileVersion", reviewProfileVersion);
        fields.put("ruleVersion", ruleVersion);
        fields.put("modelPolicyVersion", modelPolicyVersion);
        List<Map<String, Object>> selected = new ArrayList<>();
        for (var material : materials.stream().sorted(Comparator.comparing(value -> value.fileId().toString())).toList()) {
            if (!material.projectId().equals(projectId)) throw new IllegalArgumentException("cross-project run material");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ordinal", material.ordinal());
            item.put("projectId", material.projectId().toString());
            item.put("fileId", material.fileId().toString());
            item.put("fileSha256", material.fileSha256());
            item.put("parseResultId", material.parseResultId().toString());
            item.put("parseVersion", material.parseVersion());
            item.put("canonicalTextSha256", material.canonicalTextSha256());
            item.put("effectiveRole", material.effectiveRole().name());
            item.put("roleDecisionId", id(material.roleDecisionId()));
            item.put("identityDecisionId", material.identityDecisionId().toString());
            item.put("sourceRoleDispositionId", id(material.sourceRoleDispositionId()));
            item.put("selectedBy", material.selectedBy());
            selected.add(item);
        }
        fields.put("materials", selected);
        return ContractSnapshotCanonicalizer.sha256(json(fields));
    }

    public String commandFingerprint(String actorId, UUID projectId, UUID contractId,
            long expectedContractVersion, List<UUID> requestedFileIds, String inputSnapshotIdentity) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("operation", "REVIEW_RUN_CREATE-v1");
        fields.put("actorId", actorId);
        fields.put("projectId", projectId.toString());
        fields.put("contractId", contractId.toString());
        fields.put("expectedContractVersion", expectedContractVersion);
        fields.put("materialFileIds", requestedFileIds.stream().map(UUID::toString).sorted().toList());
        fields.put("inputSnapshotIdentity", inputSnapshotIdentity);
        return ContractSnapshotCanonicalizer.sha256(json(fields));
    }

    private static String id(UUID value) { return value == null ? null : value.toString(); }
    private static String json(Map<String, Object> fields) {
        try { return JSON.writeValueAsString(fields); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("manifest serialization failed", failure); }
    }
}
