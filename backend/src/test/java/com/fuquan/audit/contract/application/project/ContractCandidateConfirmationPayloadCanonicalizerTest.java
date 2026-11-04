package com.fuquan.audit.contract.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fuquan.audit.common.exception.BusinessErrorCode;
import com.fuquan.audit.common.exception.BusinessException;
import com.fuquan.audit.contract.domain.project.ContractCandidateConfirmation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractCandidateConfirmationPayloadCanonicalizerTest {
    private final ContractCandidateConfirmationPayloadCanonicalizer canonicalizer =
            new ContractCandidateConfirmationPayloadCanonicalizer();

    @Test
    void canonicalizesStringsDecimalsAndLineOrderWithoutFloatingPoint() {
        var first = payload(List.of(line("Ｂ", 2, "10.00"), line("Ａ", 1, "20.000")));
        var second = payload(List.of(line("A", 1, "20"), line("B", 2, "10")));
        var a = canonicalizer.canonicalize(first);
        var b = canonicalizer.canonicalize(second);
        assertThat(a.json()).isEqualTo(b.json());
        assertThat(a.payload().contractNo()).isEqualTo("C-1");
        assertThat(a.payload().contractAmount()).isEqualByComparingTo("100");
        assertThat(a.json()).contains("\"contractAmount\":\"100\"", "\"winningDate\":\"2026-09-15\"");
        assertThat(a.payload().lineItems()).extracting(ConfirmedContractPayload.ConfirmedLineItem::itemKey)
                .containsExactly("A", "B");
        assertThat(a.schemaVersion()).isEqualTo(2);
        assertThat(a.json()).contains("\"basis\":\"QUANTITY_PRICE\"");
    }

    @Test
    void canonicalizesAndReopensAmountOnlyWithoutInventingQuantityOrUnitPrice() {
        var amountOnly = new ConfirmedContractPayload.ConfirmedLineItem("SERVICE-1", "服务一",
                com.fuquan.audit.contract.domain.ContractLineItemBasis.AMOUNT_ONLY,
                null, null, new BigDecimal("28000.00"), 1);
        var value = canonicalizer.canonicalize(payload(List.of(amountOnly)));

        assertThat(value.json()).contains("\"basis\":\"AMOUNT_ONLY\"",
                "\"quantity\":null", "\"unitPrice\":null", "\"declaredAmount\":\"28000\"");
        assertThat(canonicalizer.decodeAndVerify(confirmation(value.json(), value.schemaVersion(), null)))
                .isEqualTo(value.payload());
    }

    @Test
    void stillVerifiesLegacySchemaV1Confirmation() {
        var legacyPayload = payload(List.of(line("A", 1, "20")));
        String legacyJson = canonicalizer.canonicalizeLegacyV1(legacyPayload).json();
        assertThat(canonicalizer.decodeAndVerify(confirmation(legacyJson, 1, null)).lineItems().get(0)
                .basis().name()).isEqualTo("QUANTITY_PRICE");
    }

    @Test
    void rejectsDuplicateNormalizedLineKeys() {
        assertThatThrownBy(() -> canonicalizer.canonicalize(payload(List.of(
                line("Ａ", 1, "20"), line("A", 2, "10")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fingerprintBindsAuthorityVersionAndEntireCanonicalPayload() {
        var value = canonicalizer.canonicalize(payload(List.of(line("A", 1, "20"))));
        var ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String one = canonicalizer.fingerprint(ids.get(0), ids.get(1), ids.get(2), ids.get(3),
                UUID.randomUUID(), null, 3, value.json());
        assertThat(one).matches("[0-9a-f]{64}");
        assertThat(one).isNotEqualTo(canonicalizer.fingerprint(ids.get(0), ids.get(1), ids.get(2), ids.get(3),
                UUID.randomUUID(), null, 4, value.json()));
    }

    @Test
    void reopenFailsClosedForUnsupportedSchemaTamperedHashAndNoncanonicalJson() {
        var value = canonicalizer.canonicalize(payload(List.of(line("A", 1, "20"))));
        var c = confirmation(value.json(), value.schemaVersion(), null);
        assertThat(canonicalizer.decodeAndVerify(c)).isEqualTo(value.payload());
        assertThatThrownBy(() -> canonicalizer.decodeAndVerify(confirmation(value.json(), 99,
                "0".repeat(64)))).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).errorCode())
                        .isEqualTo(BusinessErrorCode.CONFLICT));
        assertThatThrownBy(() -> canonicalizer.decodeAndVerify(confirmation(value.json(), 1,
                "0".repeat(64)))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> canonicalizer.decodeAndVerify(confirmation("{}", 1,
                null))).isInstanceOf(BusinessException.class);
    }

    private static ContractCandidateConfirmation confirmation(String json, int schema, String fingerprint) {
        UUID project = UUID.randomUUID(), candidate = UUID.randomUUID(), file = UUID.randomUUID(),
                parse = UUID.randomUUID(), role = UUID.randomUUID(), identity = UUID.randomUUID();
        String binding = fingerprint == null ? new ContractCandidateConfirmationPayloadCanonicalizer().fingerprint(
                project, candidate, file, parse, role, identity, 3, schema, json) : fingerprint;
        return new ContractCandidateConfirmation(UUID.randomUUID(), project, candidate,
                3, 4, file, parse, role, identity,
                schema, binding, json, UUID.randomUUID().toString(), Instant.now(), null, Instant.now());
    }

    private static ConfirmedContractPayload payload(List<ConfirmedContractPayload.ConfirmedLineItem> lines) {
        return new ConfirmedContractPayload(" Ｃ－１ ", "  Test   Contract ", "GOODS", "甲方", "乙方", "  ",
                new BigDecimal("100.00"), null, lines,
                new ConfirmedContractPayload.ConfirmedAwardNotice("N-1", "Project", "Supplier", null,
                        new BigDecimal("100.00"), LocalDate.of(2026, 9, 15), null));
    }

    private static ConfirmedContractPayload.ConfirmedLineItem line(String key, int sortNo, String amount) {
        return new ConfirmedContractPayload.ConfirmedLineItem(key, "Item " + key, BigDecimal.ONE,
                new BigDecimal(amount), new BigDecimal(amount), sortNo);
    }
}
