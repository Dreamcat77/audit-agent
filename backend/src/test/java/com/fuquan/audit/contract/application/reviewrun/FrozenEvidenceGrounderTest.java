package com.fuquan.audit.contract.application.reviewrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.project.DocumentRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FrozenEvidenceGrounderTest {
    private final FrozenEvidenceGrounder grounder = new FrozenEvidenceGrounder();

    @Test
    void uniqueRawExcerptAfterSurrogatePrefixUsesCodePointOffsetsAndExactSha() {
        var result = grounder.ground(material("😀甲乙违约条款"), "违约", true);

        assertThat(result.locatorAvailable()).isTrue();
        assertThat(result.startOffset()).isEqualTo(3);
        assertThat(result.endOffset()).isEqualTo(5);
        assertThat(result.excerptSha256()).isEqualTo(
                "cf4b3a69084877da5d0ae014775be748d4dcc359ccade84a73aa4b018be1bec3");
        assertThat(result.boundedExcerpt()).isEqualTo("违约");
    }

    @Test
    void repeatedModelExcerptCannotInventFirstMatchLocator() {
        var result = grounder.ground(material("重复重复"), "重复", false);

        assertThat(result.locatorAvailable()).isFalse();
        assertThat(result.startOffset()).isNull();
        assertThat(result.endOffset()).isNull();
        assertThat(result.excerptSha256()).isEqualTo(sha("重复"));
        assertThat(result.boundedExcerpt()).isEqualTo("重复");
    }

    @Test
    void normalizationOnlyModelExcerptCannotInventLocator() {
        var result = grounder.ground(material("合同e\u0301条款"), "\u00e9", false);

        assertThat(result.locatorAvailable()).isFalse();
        assertThat(result.startOffset()).isNull();
        assertThat(result.endOffset()).isNull();
        assertThat(result.excerptSha256()).isEqualTo(sha("\u00e9"));
    }

    @Test
    void ungroundableDeterministicExcerptConflictsInsteadOfProducingEvidence() {
        assertThatThrownBy(() -> grounder.ground(material("合同正文"), "不存在", true))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(BusinessErrorCode.CONFLICT));
        assertThatThrownBy(() -> grounder.ground(material("重复重复"), "重复", true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void overlongExcerptAndUnverifiedFrozenTextFailClosed() {
        assertThatThrownBy(() -> grounder.ground(material("甲".repeat(1001)), "甲".repeat(1001), false))
                .isInstanceOf(BusinessException.class);
        var altered = material("合同正文");
        var tampered = new FrozenReviewMaterial(altered.runMaterialId(), altered.ordinal(), altered.fileId(),
                altered.fileSha256(), altered.parseResultId(), altered.parseVersion(), "b".repeat(64),
                altered.canonicalText(), altered.effectiveRole(), altered.roleDecisionId(),
                altered.identityDecisionId(), altered.sourceRoleDispositionId());
        assertThatThrownBy(() -> grounder.ground(tampered, "合同", false))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void excerptCannotSplitASurrogatePairAndClaimACodePointSpan() {
        assertThatThrownBy(() -> grounder.ground(material("😀条款"), "\uD83D", true))
                .isInstanceOf(BusinessException.class);
        var model = grounder.ground(material("😀条款"), "\uD83D", false);
        assertThat(model.locatorAvailable()).isFalse();
        assertThat(model.startOffset()).isNull();
    }

    private static FrozenReviewMaterial material(String text) {
        return new FrozenReviewMaterial(UUID.randomUUID(), 1, UUID.randomUUID(), "a".repeat(64),
                UUID.randomUUID(), 1, sha(text), text, DocumentRole.CONTRACT_BODY,
                null, UUID.randomUUID(), null);
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
