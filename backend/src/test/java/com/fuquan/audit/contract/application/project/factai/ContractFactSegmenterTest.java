package com.fuquan.audit.contract.application.project.factai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractFactSegmenterTest {
    private static final UUID PARSE = UUID.fromString("34000000-0000-0000-0000-000000000001");

    @Test
    void groupedThousandsReceiveMappingButMalformedCommasAndUnitsNeverDo() {
        String header="名称\t数量\t单价\t金额\n";
        String valid=header+"服务\t2\t1,234.00\t2,468.00\n";
        var validSegments=new ContractFactSegmenter().segment(PARSE,valid);
        assertThat(validSegments.get(1).lineItemColumns())
                .containsExactly("name","quantity","unitPrice","declaredAmount");
        assertThat(validSegments.stream().map(segment->segment.text()).collect(java.util.stream.Collectors.joining()))
                .isEqualTo(valid);
        for(String invalid:java.util.List.of("1,00","12,34,567","1,234,","¥1,234","1,234元","01,234","+1,234","-1,234")) {
            var segments=new ContractFactSegmenter().segment(PARSE,header+"服务\t2\t"+invalid+"\t2,468.00\n");
            assertThat(segments.get(1).lineItemColumns()).as("malformed grouping or units cannot grant compact authority").isNull();
        }
    }

    @Test
    void ambiguousInvalidAndFragmentedRowsNeverReceiveCompactAuthority() {
        String header = "名称\t数量\t单价\t金额\n";
        for (String text : java.util.List.of(
                "服务\t2\t100\t200\n",
                "名称\t数量\t数量\t金额\n服务\t2\t100\t200\n",
                header + "服务\t2件\t100\t200\n服务\t2\t100\t200\n",
                header + "名".repeat(245) + "\t2\t100\t200\n")) {
            var segments = new ContractFactSegmenter().segment(PARSE, text);
            assertThat(segments).allSatisfy(segment -> assertThat(segment.lineItemColumns()).isNull());
            assertThat(segments.stream().map(value -> value.text()).collect(java.util.stream.Collectors.joining()))
                    .isEqualTo(text);
        }
    }

    @Test
    void compactColumnAuthorityComesFromTheExplicitHeaderNotTheNumberOfCells() {
        String text="行号\t名称\t单价（元）\t数量\t金额（元）\nA-1\t服务\t100.00\t2\t200.00\n正文\nB-1\t服务\t100.00\t2\t200.00";
        var segments=new ContractFactSegmenter().segment(PARSE,text);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var row=mapper.valueToTree(segments.get(1));
        assertThat(row.hasNonNull("lineItemColumns")).isTrue();
        assertThat(row.get("lineItemColumns").toString())
                .isEqualTo("[\"rowId\",\"name\",\"unitPrice\",\"quantity\",\"declaredAmount\"]");
        assertThat(mapper.valueToTree(segments.get(0)).hasNonNull("lineItemColumns")).isFalse();
        assertThat(mapper.valueToTree(segments.get(3)).hasNonNull("lineItemColumns")).isFalse();
        assertThat(segments.stream().map(value->value.text()).collect(java.util.stream.Collectors.joining())).isEqualTo(text);
    }

    @Test
    void createsStableBoundedCodePointSegmentsWithExactLocators() {
        String text = "合同编号：HT-01\n" + "𠀀".repeat(230) + "\n成交金额：67000元";

        var segments = new ContractFactSegmenter().segment(PARSE, text);

        assertThat(segments).hasSize(3);
        assertThat(segments).allSatisfy(segment -> {
            assertThat(segment.text().codePointCount(0, segment.text().length())).isLessThanOrEqualTo(240);
            assertThat(segment.sourceLocator()).isEqualTo("parse-result:" + PARSE
                    + ":canonical-codepoint:" + segment.startOffset() + "-" + segment.endOffset());
        });
        assertThat(segments.get(0).startOffset()).isZero();
        assertThat(segments.get(2).endOffset()).isEqualTo(text.codePointCount(0, text.length()));
    }

    @Test
    void coversCompleteUnicodeDocumentsAcrossTheOldSingleRequestBoundary() {
        var segmenter = new ContractFactSegmenter();
        for (int length : new int[]{7679, 7680, 7681, 8235}) {
            String text = "𠀀".repeat(length - 10) + "合同金额67000元";
            var segments = segmenter.segment(PARSE, text);
            assertThat(segments.stream().map(value -> value.text())
                    .collect(java.util.stream.Collectors.joining())).isEqualTo(text);
            assertThat(segments.get(segments.size() - 1).endOffset()).isEqualTo(length);
            assertThat(segments).allSatisfy(value ->
                    assertThat(value.text().codePointCount(0, value.text().length())).isLessThanOrEqualTo(240));
        }
        String expanded = "a".repeat(30721);
        var expandedSegments = segmenter.segment(PARSE, expanded);
        assertThat(expandedSegments.stream().map(value -> value.text())
                .collect(java.util.stream.Collectors.joining())).isEqualTo(expanded);
        assertThatThrownBy(() -> segmenter.segment(PARSE, "a".repeat(122881)))
                .isInstanceOfSatisfying(ContractFactSegmentationException.class, failure -> {
                    assertThat(failure.reasonCode()).isEqualTo("INPUT_TOO_LARGE");
                    assertThat(failure.canonicalCodePointCount()).isEqualTo(122881);
                });
    }

    @Test
    void coversParagraphFragmentsWithoutTruncatingAtTheThirtyThirdSegment() {
        String text = ("a".repeat(120) + "\n").repeat(33);

        assertThat(text.codePointCount(0, text.length())).isLessThan(7680);
        var segments = new ContractFactSegmenter().segment(PARSE, text);
        assertThat(segments).hasSize(33);
        assertThat(segments.stream().map(value -> value.text()).collect(java.util.stream.Collectors.joining()))
                .isEqualTo(text);
    }

    @Test
    void keepsACompleteNearbyParagraphInsteadOfSplittingItsFactAcrossEvidenceItems() {
        String first = "a".repeat(150) + "\n";
        String text = first + "contract amount RMB 67000 and uppercase amount" + "b".repeat(90);

        var segments = new ContractFactSegmenter().segment(PARSE, text);

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).text()).isEqualTo(first);
        assertThat(segments.get(0).endOffset()).isEqualTo(first.length());
        assertThat(segments.stream().map(value -> value.text()).collect(java.util.stream.Collectors.joining()))
                .isEqualTo(text);
    }

    @Test
    void originalRowsKeepIdentityAcrossFragmentsButNeverShareItWithAnotherRow() {
        String text = "𠀀".repeat(481) + "\nsecond row";
        var segments = new ContractFactSegmenter().segment(PARSE, text);
        assertThat(segments).hasSize(4);
        assertThat(segments.subList(0, 3)).extracting(value -> value.rowIdentity()).containsOnly(
                segments.get(0).rowIdentity());
        assertThat(segments.get(3).rowIdentity()).isNotEqualTo(segments.get(0).rowIdentity());
        assertThat(segments.get(2).endOffset()).isEqualTo(482);
        assertThat(segments.get(3).startOffset()).isEqualTo(482);
        String veryManyRows = "x\n".repeat(4097);
        assertThatThrownBy(() -> new ContractFactSegmenter().segment(PARSE, veryManyRows))
                .isInstanceOfSatisfying(ContractFactSegmentationException.class, failure -> {
                    assertThat(failure.reasonCode()).isEqualTo("INPUT_TOO_LARGE");
                    assertThat(failure.requiredSegmentCount()).isEqualTo(4097);
                });
    }
}
