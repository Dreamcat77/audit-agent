package com.fuquan.audit.contract.application.reviewrun;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractLineItem;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Canonical, server-owned structured Contract snapshot; never serializes client or model claims. */
@Component
public class ContractSnapshotCanonicalizer {
    public static final int LEGACY_SCHEMA_VERSION = 1;
    public static final int SCHEMA_VERSION = 2;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Canonicalized(String canonicalJson, String sha256) { }

    public Canonicalized canonicalize(Contract contract) {
        return canonicalize(contract, SCHEMA_VERSION);
    }

    /** Used only to verify historical immutable snapshots; new runs always write v2. */
    public Canonicalized canonicalizeLegacyV1(Contract contract) {
        if (contract.lineItems().stream().anyMatch(line ->
                line.basis() != com.fuquan.audit.contract.domain.ContractLineItemBasis.QUANTITY_PRICE)) {
            throw new IllegalArgumentException("legacy snapshot cannot represent amount-only lines");
        }
        return canonicalize(contract, LEGACY_SCHEMA_VERSION);
    }

    private Canonicalized canonicalize(Contract contract, int schemaVersion) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("schemaVersion", schemaVersion);
        fields.put("contractId", contract.id().toString());
        fields.put("versionNo", contract.versionNo());
        fields.put("contractNo", text(contract.contractNo()));
        fields.put("contractName", text(contract.contractName()));
        fields.put("contractType", text(contract.contractType()));
        fields.put("partyA", text(contract.partyA()));
        fields.put("partyB", text(contract.partyB()));
        fields.put("partyBCreditCode", text(contract.partyBCreditCode()));
        fields.put("contractAmount", decimal(contract.contractAmount().amount()));
        fields.put("amountInWords", text(contract.amountInWords()));
        fields.put("status", text(contract.status()));
        fields.put("createdAt", contract.createdAt().toString());
        fields.put("updatedAt", contract.updatedAt().toString());
        List<ContractLineItem> sorted = contract.lineItems().stream()
                .sorted(Comparator.comparingInt(ContractLineItem::sortNo)
                        .thenComparing(item -> text(item.itemKey()))
                        .thenComparing(item -> item.id().toString()))
                .toList();
        List<Map<String, Object>> lines = new ArrayList<>();
        for (var line : sorted) {
            if (!line.contractId().equals(contract.id())) {
                throw new IllegalArgumentException("line item belongs to another contract");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", line.id().toString());
            item.put("itemKey", text(line.itemKey()));
            item.put("itemName", text(line.itemName()));
            if (schemaVersion >= SCHEMA_VERSION) item.put("basis", line.basis().name());
            item.put("quantity", nullableDecimal(line.quantity()));
            item.put("unitPrice", nullableDecimal(line.unitPrice()));
            item.put("declaredAmount", decimal(line.declaredAmount().amount()));
            item.put("sortNo", line.sortNo());
            lines.add(item);
        }
        fields.put("lineItems", lines);
        if (contract.awardNotice() == null) {
            fields.put("awardNotice", null);
        } else {
            var award = contract.awardNotice();
            if (!award.contractId().equals(contract.id())) {
                throw new IllegalArgumentException("award notice belongs to another contract");
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", award.id().toString());
            value.put("noticeNo", text(award.noticeNo()));
            value.put("projectName", text(award.projectName()));
            value.put("winningBidder", text(award.winningBidder()));
            value.put("winningBidderCreditCode", text(award.winningBidderCreditCode()));
            value.put("winningAmount", decimal(award.winningAmount().amount()));
            value.put("winningDate", award.winningDate().toString());
            value.put("sourceRef", text(award.sourceRef()));
            fields.put("awardNotice", value);
        }
        try {
            String json = JSON.writeValueAsString(fields);
            return new Canonicalized(json, sha256(json));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("contract snapshot serialization failed", failure);
        }
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by Java 17", failure);
        }
    }

    private static String text(String value) {
        if (value == null) return null;
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("(?U)\\s+", " ").trim();
    }

    private static String decimal(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? "0" : normalized.toPlainString();
    }

    private static String nullableDecimal(BigDecimal value) {
        return value == null ? null : decimal(value);
    }
}
