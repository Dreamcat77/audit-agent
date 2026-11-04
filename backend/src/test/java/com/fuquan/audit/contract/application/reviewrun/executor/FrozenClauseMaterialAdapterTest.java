package com.fuquan.audit.contract.application.reviewrun.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.project.ProjectFileCategory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FrozenClauseMaterialAdapterTest {
    @Test
    void adaptsOnlyFrozenRoleAndCanonicalTextWithStableSafeLabels() {
        UUID contractFile = UUID.fromString("20000000-0000-0000-0000-000000000001");
        UUID tenderFile = UUID.fromString("20000000-0000-0000-0000-000000000002");
        var frozen = List.of(
                FrozenClauseExecutorTestSupport.material(1, contractFile,
                        DocumentRole.CONTRACT_BODY, "冻结合同正文"),
                FrozenClauseExecutorTestSupport.material(2, tenderFile,
                        DocumentRole.TENDER_DOCUMENT, "冻结采购文件"));

        var adapted = new FrozenClauseMaterialAdapter().adapt(frozen);

        assertThat(adapted).hasSize(2);
        assertThat(adapted.get(0).fileId()).isEqualTo(contractFile);
        assertThat(adapted.get(0).fileName()).isEqualTo("frozen-material-1-CONTRACT_BODY");
        assertThat(adapted.get(0).category()).isEqualTo(ProjectFileCategory.CONTRACT_BODY);
        assertThat(adapted.get(0).text()).isEqualTo("冻结合同正文");
        assertThat(adapted.get(1).fileId()).isEqualTo(tenderFile);
        assertThat(adapted.get(1).fileName()).isEqualTo("frozen-material-2-TENDER_DOCUMENT");
        assertThat(adapted.get(1).category()).isEqualTo(ProjectFileCategory.TENDER_DOCUMENT);
        assertThat(adapted.get(1).text()).isEqualTo("冻结采购文件");
    }

    @Test
    void preservesVerifiedSourceFilenameForDeterministicAttachmentMatching() {
        UUID attachmentFile = UUID.fromString("20000000-0000-0000-0000-000000000003");
        var base = FrozenClauseExecutorTestSupport.material(
                3, attachmentFile, DocumentRole.SUPPLEMENT, "技术参数正文");
        var named = new com.fuquan.audit.contract.application.reviewrun.FrozenReviewMaterial(
                base.runMaterialId(), base.ordinal(), base.fileId(), base.fileSha256(),
                base.parseResultId(), base.parseVersion(), base.canonicalTextSha256(),
                base.canonicalText(), base.effectiveRole(), base.roleDecisionId(),
                base.identityDecisionId(), base.sourceRoleDispositionId(), "技术规格书.pdf");

        assertThat(new FrozenClauseMaterialAdapter().adapt(List.of(named))).singleElement()
                .extracting(value -> value.fileName()).isEqualTo("技术规格书.pdf");
    }
}
