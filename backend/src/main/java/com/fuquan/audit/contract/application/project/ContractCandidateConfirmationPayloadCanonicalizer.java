package com.fuquan.audit.contract.application.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.project.ContractCandidateConfirmation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Defines one server-owned JSON schema, independent of HTTP object key order and BigDecimal scale. */
@Component
public class ContractCandidateConfirmationPayloadCanonicalizer {
    public static final int LEGACY_SCHEMA_VERSION = 1;
    public static final int SCHEMA_VERSION = 2;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Canonicalized(int schemaVersion, String json, ConfirmedContractPayload payload,
                                String payloadFingerprint) { }

    public Canonicalized canonicalize(ConfirmedContractPayload input) {
        return canonicalize(input, SCHEMA_VERSION);
    }

    /** Used only for immutable historical confirmation verification. */
    public Canonicalized canonicalizeLegacyV1(ConfirmedContractPayload input) {
        if (input.lineItems().stream().anyMatch(line -> line.basis()
                != com.fuquan.audit.contract.domain.ContractLineItemBasis.QUANTITY_PRICE)) {
            throw new IllegalArgumentException("legacy confirmation cannot represent amount-only lines");
        }
        return canonicalize(input, LEGACY_SCHEMA_VERSION);
    }

    private Canonicalized canonicalize(ConfirmedContractPayload input, int schemaVersion) {
        var indexed = new ArrayList<IndexedLine>();
        for (int i = 0; i < input.lineItems().size(); i++) {
            var source = input.lineItems().get(i);
            indexed.add(new IndexedLine(i, new ConfirmedContractPayload.ConfirmedLineItem(
                    required(source.itemKey()), required(source.itemName()), source.basis(),
                    nullableDecimalValue(source.quantity()), nullableDecimalValue(source.unitPrice()),
                    decimalValue(source.declaredAmount()), source.sortNo())));
        }
        indexed.sort(Comparator.comparingInt((IndexedLine v) -> v.line.sortNo())
                .thenComparing(v -> v.line.itemKey()).thenComparingInt(v -> v.index));
        var award = input.awardNotice();
        ConfirmedContractPayload.ConfirmedAwardNotice normalizedAward = award == null ? null
                : new ConfirmedContractPayload.ConfirmedAwardNotice(required(award.noticeNo()),
                        required(award.projectName()), required(award.winningBidder()),
                        nullable(award.winningBidderCreditCode()), decimalValue(award.winningAmount()),
                        award.winningDate(), nullable(award.sourceRef()));
        var value = new ConfirmedContractPayload(required(input.contractNo()), required(input.contractName()),
                required(input.contractType()), required(input.partyA()), required(input.partyB()),
                nullable(input.partyBCreditCode()), decimalValue(input.contractAmount()),
                nullable(input.amountInWords()), indexed.stream().map(IndexedLine::line).toList(), normalizedAward);
        Map<String, Object> fields = orderedFields(value, schemaVersion);
        try {
            String text = JSON.writeValueAsString(fields);
            return new Canonicalized(schemaVersion, text, value, sha256(text));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("server canonical payload serialization failed", failure);
        }
    }

    public ConfirmedContractPayload decodeAndVerify(ContractCandidateConfirmation confirmation) {
        try {
            int schemaVersion = confirmation.payloadSchemaVersion();
            if (schemaVersion != LEGACY_SCHEMA_VERSION && schemaVersion != SCHEMA_VERSION)
                throw new IllegalArgumentException();
            JsonNode json = JSON.readTree(confirmation.canonicalPayloadJson());
            if (json == null || !json.isObject()) throw new IllegalArgumentException();
            List<ConfirmedContractPayload.ConfirmedLineItem> lines = new ArrayList<>();
            JsonNode lineNodes = requiredNode(json, "lineItems");
            if (!lineNodes.isArray()) throw new IllegalArgumentException();
            for (JsonNode line : lineNodes) {
                lines.add(new ConfirmedContractPayload.ConfirmedLineItem(text(line, "itemKey"),
                        text(line, "itemName"),
                        schemaVersion == LEGACY_SCHEMA_VERSION
                                ? com.fuquan.audit.contract.domain.ContractLineItemBasis.QUANTITY_PRICE
                                : com.fuquan.audit.contract.domain.ContractLineItemBasis.valueOf(text(line, "basis")),
                        nullableDecimal(line, "quantity"), nullableDecimal(line, "unitPrice"),
                        decimal(line, "declaredAmount"), integer(line, "sortNo")));
            }
            JsonNode award = requiredNode(json, "awardNotice");
            ConfirmedContractPayload.ConfirmedAwardNotice awardValue = award.isNull() ? null
                    : new ConfirmedContractPayload.ConfirmedAwardNotice(text(award, "noticeNo"),
                            text(award, "projectName"), text(award, "winningBidder"),
                            text(award, "winningBidderCreditCode"), decimal(award, "winningAmount"),
                            LocalDate.parse(text(award, "winningDate")), text(award, "sourceRef"));
            var decoded = new ConfirmedContractPayload(text(json, "contractNo"), text(json, "contractName"),
                    text(json, "contractType"), text(json, "partyA"), text(json, "partyB"),
                    text(json, "partyBCreditCode"), decimal(json, "contractAmount"),
                    text(json, "amountInWords"), lines, awardValue);
            Canonicalized canonical = schemaVersion == LEGACY_SCHEMA_VERSION
                    ? canonicalizeLegacyV1(decoded) : canonicalize(decoded);
            String fingerprint = fingerprint(confirmation.projectId(), confirmation.candidateId(),
                    confirmation.sourceFileId(), confirmation.sourceParseResultId(),
                    confirmation.sourceRoleDecisionId(), confirmation.sourceIdentityDecisionId(),
                    confirmation.baseCandidateVersionNo(), schemaVersion, canonical.json());
            if (!canonical.json().equals(confirmation.canonicalPayloadJson())
                    || !fingerprint.equals(confirmation.payloadFingerprint())) throw new IllegalArgumentException();
            return canonical.payload();
        } catch (Exception failure) {
            throw new BusinessException(BusinessErrorCode.CONFLICT,
                    "candidate confirmation payload cannot be verified", failure);
        }
    }

    public String fingerprint(UUID projectId, UUID candidateId, UUID sourceFileId, UUID parseResultId,
            UUID roleDecisionId, UUID identityDecisionId, long baseVersion, String canonicalJson) {
        return fingerprint(projectId, candidateId, sourceFileId, parseResultId, roleDecisionId,
                identityDecisionId, baseVersion, SCHEMA_VERSION, canonicalJson);
    }

    public String fingerprint(UUID projectId, UUID candidateId, UUID sourceFileId, UUID parseResultId,
            UUID roleDecisionId, UUID identityDecisionId, long baseVersion, int schemaVersion,
            String canonicalJson) {
        if (schemaVersion != LEGACY_SCHEMA_VERSION && schemaVersion != SCHEMA_VERSION)
            throw new IllegalArgumentException("unsupported confirmation schema");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, "contract-candidate-confirmation-payload-v" + schemaVersion);
            add(digest, projectId.toString()); add(digest, candidateId.toString());
            add(digest, sourceFileId.toString()); add(digest, parseResultId.toString());
            add(digest, roleDecisionId == null ? null : roleDecisionId.toString());
            add(digest, identityDecisionId == null ? null : identityDecisionId.toString());
            add(digest, Long.toString(baseVersion)); add(digest, Integer.toString(schemaVersion));
            add(digest, canonicalJson);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required by Java 17", failure);
        }
    }

    private static Map<String, Object> orderedFields(ConfirmedContractPayload value, int schemaVersion) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("contractNo", value.contractNo()); fields.put("contractName", value.contractName());
        fields.put("contractType", value.contractType()); fields.put("partyA", value.partyA());
        fields.put("partyB", value.partyB()); fields.put("partyBCreditCode", value.partyBCreditCode());
        fields.put("contractAmount", decimalText(value.contractAmount()));
        fields.put("amountInWords", value.amountInWords());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (var line : value.lineItems()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("itemKey", line.itemKey()); entry.put("itemName", line.itemName());
            if (schemaVersion >= SCHEMA_VERSION) entry.put("basis", line.basis().name());
            entry.put("quantity", nullableDecimalText(line.quantity()));
            entry.put("unitPrice", nullableDecimalText(line.unitPrice()));
            entry.put("declaredAmount", decimalText(line.declaredAmount())); entry.put("sortNo", line.sortNo());
            lines.add(entry);
        }
        fields.put("lineItems", lines);
        var award = value.awardNotice();
        if (award == null) fields.put("awardNotice", null);
        else {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("noticeNo", award.noticeNo()); entry.put("projectName", award.projectName());
            entry.put("winningBidder", award.winningBidder());
            entry.put("winningBidderCreditCode", award.winningBidderCreditCode());
            entry.put("winningAmount", decimalText(award.winningAmount()));
            entry.put("winningDate", award.winningDate().toString()); entry.put("sourceRef", award.sourceRef());
            fields.put("awardNotice", entry);
        }
        return fields;
    }

    private static JsonNode requiredNode(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null) throw new IllegalArgumentException("missing canonical field");
        return value;
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = requiredNode(node, key);
        if (value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("invalid canonical text field");
        return value.textValue();
    }

    private static BigDecimal decimal(JsonNode node, String key) {
        String value = text(node, key);
        if (value == null) throw new IllegalArgumentException("missing canonical decimal");
        return new BigDecimal(value);
    }

    private static BigDecimal nullableDecimal(JsonNode node, String key) {
        String value = text(node, key);
        return value == null ? null : new BigDecimal(value);
    }

    private static int integer(JsonNode node, String key) {
        JsonNode value = requiredNode(node, key);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw new IllegalArgumentException();
        return value.intValue();
    }

    private static String required(String value) {
        String normalized = nullable(value);
        if (normalized == null) throw new IllegalArgumentException("required canonical text is blank");
        return normalized;
    }

    private static String nullable(String value) {
        if (value == null) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("(?U)\\s+", " ").trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static BigDecimal decimalValue(BigDecimal value) { return value.stripTrailingZeros(); }
    private static BigDecimal nullableDecimalValue(BigDecimal value) {
        return value == null ? null : decimalValue(value);
    }
    private static String decimalText(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static String nullableDecimalText(BigDecimal value) {
        return value == null ? null : decimalText(value);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24)); digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8)); digest.update((byte) bytes.length); digest.update(bytes);
    }

    private record IndexedLine(int index, ConfirmedContractPayload.ConfirmedLineItem line) { }
}
