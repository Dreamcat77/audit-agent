package com.fuquan.audit.contract.application.reviewrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.AwardNoticeReference;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractLineItem;
import com.fuquan.audit.contract.domain.ContractLineItemBasis;
import com.fuquan.audit.contract.domain.Money;
import com.fuquan.audit.contract.domain.reviewrun.ContractReviewRun;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Decodes only the verified canonical run snapshot; never fills gaps from the current Contract. */
@Component
public class ContractSnapshotDecoder {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ROOT_KEYS = Set.of("schemaVersion", "contractId", "versionNo",
            "contractNo", "contractName", "contractType", "partyA", "partyB",
            "partyBCreditCode", "contractAmount", "amountInWords", "status",
            "createdAt", "updatedAt", "lineItems", "awardNotice");
    private static final Set<String> LINE_KEYS_V1 = Set.of("id", "itemKey", "itemName", "quantity",
            "unitPrice", "declaredAmount", "sortNo");
    private static final Set<String> LINE_KEYS_V2 = Set.of("id", "itemKey", "itemName", "basis", "quantity",
            "unitPrice", "declaredAmount", "sortNo");
    private static final Set<String> AWARD_KEYS = Set.of("id", "noticeNo", "projectName",
            "winningBidder", "winningBidderCreditCode", "winningAmount", "winningDate", "sourceRef");
    private final ContractSnapshotCanonicalizer canonicalizer;

    public ContractSnapshotDecoder(ContractSnapshotCanonicalizer canonicalizer) {
        this.canonicalizer = canonicalizer;
    }

    public Contract decodeAndVerify(ContractReviewRun run) {
        String raw = run.contractSnapshotCanonicalJson();
        if (!ContractSnapshotCanonicalizer.sha256(raw).equals(run.contractSnapshotSha256()))
            throw conflict();
        try {
            JsonNode root = JSON.readTree(raw);
            requireKeys(root, ROOT_KEYS);
            int schemaVersion = Math.toIntExact(number(root, "schemaVersion"));
            if (schemaVersion != ContractSnapshotCanonicalizer.LEGACY_SCHEMA_VERSION
                    && schemaVersion != ContractSnapshotCanonicalizer.SCHEMA_VERSION)
                throw conflict();
            UUID contractId = UUID.fromString(text(root, "contractId"));
            long versionNo = number(root, "versionNo");
            if (!contractId.equals(run.contractId()) || versionNo != run.contractVersion())
                throw conflict();
            JsonNode linesNode = root.get("lineItems");
            if (!linesNode.isArray()) throw conflict();
            List<ContractLineItem> lines = new ArrayList<>();
            for (JsonNode node : linesNode) {
                requireKeys(node, schemaVersion == ContractSnapshotCanonicalizer.LEGACY_SCHEMA_VERSION
                        ? LINE_KEYS_V1 : LINE_KEYS_V2);
                ContractLineItemBasis basis = schemaVersion == ContractSnapshotCanonicalizer.LEGACY_SCHEMA_VERSION
                        ? ContractLineItemBasis.QUANTITY_PRICE
                        : ContractLineItemBasis.valueOf(text(node, "basis"));
                lines.add(new ContractLineItem(UUID.fromString(text(node, "id")), contractId,
                        text(node, "itemKey"), text(node, "itemName"),
                        basis, nullableDecimal(node, "quantity"), nullableDecimal(node, "unitPrice"),
                        Money.of(decimal(node, "declaredAmount")), Math.toIntExact(number(node, "sortNo"))));
            }
            AwardNoticeReference award = null;
            JsonNode awardNode = root.get("awardNotice");
            if (!awardNode.isNull()) {
                requireKeys(awardNode, AWARD_KEYS);
                award = new AwardNoticeReference(UUID.fromString(text(awardNode, "id")), contractId,
                        text(awardNode, "noticeNo"), text(awardNode, "projectName"),
                        text(awardNode, "winningBidder"), nullableText(awardNode, "winningBidderCreditCode"),
                        Money.of(decimal(awardNode, "winningAmount")),
                        LocalDate.parse(text(awardNode, "winningDate")), nullableText(awardNode, "sourceRef"));
            }
            Contract decoded = new Contract(contractId, text(root, "contractNo"),
                    text(root, "contractName"), text(root, "contractType"), text(root, "partyA"),
                    text(root, "partyB"), nullableText(root, "partyBCreditCode"),
                    Money.of(decimal(root, "contractAmount")), nullableText(root, "amountInWords"),
                    text(root, "status"), versionNo, Instant.parse(text(root, "createdAt")),
                    Instant.parse(text(root, "updatedAt")), lines, award);
            String canonical = schemaVersion == ContractSnapshotCanonicalizer.LEGACY_SCHEMA_VERSION
                    ? canonicalizer.canonicalizeLegacyV1(decoded).canonicalJson()
                    : canonicalizer.canonicalize(decoded).canonicalJson();
            if (!canonical.equals(raw)) throw conflict();
            return decoded;
        } catch (RuntimeException failure) {
            throw conflict();
        } catch (java.io.IOException failure) {
            throw conflict();
        }
    }

    private static void requireKeys(JsonNode node, Set<String> keys) {
        if (node == null || !node.isObject() || node.size() != keys.size()) throw conflict();
        for (String key : keys) if (!node.has(key)) throw conflict();
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual()) throw conflict();
        return value.textValue();
    }

    private static String nullableText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null) throw conflict();
        return value.isNull() ? null : text(node, key);
    }

    private static long number(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw conflict();
        return value.longValue();
    }

    private static BigDecimal decimal(JsonNode node, String key) {
        return new BigDecimal(text(node, key));
    }

    private static BigDecimal nullableDecimal(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null) throw conflict();
        return value.isNull() ? null : decimal(node, key);
    }

    private static BusinessException conflict() {
        return new BusinessException(BusinessErrorCode.CONFLICT, "review run Contract snapshot cannot be verified");
    }
}
