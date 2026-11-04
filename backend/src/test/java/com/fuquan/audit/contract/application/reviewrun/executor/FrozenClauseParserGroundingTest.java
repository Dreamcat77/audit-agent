package com.fuquan.audit.contract.application.reviewrun.executor;

import static org.assertj.core.api.Assertions.assertThat;
import com.fuquan.audit.contract.application.clause.ContractTermClauseAnalyzer;
import com.fuquan.audit.contract.application.reviewrun.ContractSnapshotCanonicalizer;
import com.fuquan.audit.contract.application.reviewrun.FrozenEvidenceGrounder;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewStepType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FrozenClauseParserGroundingTest {
    @Test
    void newSyntaxRetainsOriginalFrozenParseTupleAndCodepointEvidenceWithoutExternalCalls() {
        String body = "😀合同\n成果于2028 年 3 月 6 日完成交付。\n第九条 附件清单\n"
                + "附件一《技术参数表》、附件九《联调记录表》。\n第十条 其他事项";
        var contract = FrozenClauseExecutorTestSupport.material(1, new UUID(0, 101), DocumentRole.CONTRACT_BODY,
                body, "合同.docx");
        var attachment = FrozenClauseExecutorTestSupport.material(2, new UUID(0, 102), DocumentRole.SUPPLEMENT,
                "技术参数表\n参数测试和验收记录。", "06_附件一_技术参数表.docx");
        var execution = FrozenClauseExecutorTestSupport.execution(ContractReviewStepType.TERM,
                new BigDecimal("10000"), List.of(contract, attachment));
        var executor = new FrozenTermReviewStepExecutor(new ContractTermClauseAnalyzer(),
                new FrozenClauseMaterialAdapter(), new FrozenClauseResultMapper(new FrozenEvidenceGrounder(),
                        FrozenClauseExecutorTestSupport.CLOCK));
        var result = executor.execute(execution.input(), execution.context());
        assertThat(result.findings()).extracting(f -> f.findingType()).containsExactly(
                "DELIVERY_DEADLINE", "ATTACHMENT_REFERENCE_MATCH", "ATTACHMENT_REFERENCE_MATCH");
        assertThat(result.findings()).extracting(f -> f.statusCode()).containsExactly("NORMAL", "NORMAL", "ATTENTION");
        assertThat(result.evidence()).hasSize(4).allSatisfy(e -> {
            var material = e.fileId().equals(contract.fileId()) ? contract : attachment;
            assertThat(e.parseResultId()).isEqualTo(material.parseResultId());
            assertThat(e.fileSha256()).isEqualTo(material.fileSha256());
            assertThat(e.canonicalTextSha256()).isEqualTo(material.canonicalTextSha256());
            assertThat(e.locatorAvailable()).isTrue();
            String text = material.canonicalText();
            String slice = text.substring(text.offsetByCodePoints(0, e.startOffset()),
                    text.offsetByCodePoints(0, e.endOffset()));
            assertThat(slice).isEqualTo(e.boundedExcerpt());
            assertThat(ContractSnapshotCanonicalizer.sha256(slice)).isEqualTo(e.excerptSha256());
        });
        assertThat(contract.canonicalText()).isEqualTo(body);
    }
}
