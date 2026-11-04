package com.fuquan.audit.contract.application.reviewrun.semantic;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuquan.audit.contract.application.ai.ContractTextAnalysisPort.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SemanticHierarchyRequestTest {
    final ObjectMapper json = new ObjectMapper();
    final UUID project = UUID.randomUUID(), file = UUID.randomUUID();
    final List<ContractTextAnalysisMaterial> materials = List.of(new ContractTextAnalysisMaterial(file, "CONTRACT_BODY", "synthetic source"));

    @Test void additiveOptInRoundTripsWithoutChangingLegacyConstructorsOrWire() throws Exception {
        for (var old : List.of(new ContractTextAnalysisRequest(project, materials),
                new ContractTextAnalysisRequest(project, materials, "contract-semantic-quality-v1"),
                new ContractTextAnalysisRequest(project, materials, "contract-semantic-quality-v1", "OBSERVE")))
            assertThat(json.valueToTree(old).has("semanticHierarchyProtocol")).isFalse();
        var wire = json.valueToTree(new ContractTextAnalysisRequest(project, materials, "contract-semantic-quality-v1", "OBSERVE"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)wire).put("semanticHierarchyProtocol", "contract-bounded-hierarchy-v1");
        var actual = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> json.treeToValue(wire, ContractTextAnalysisRequest.class));
        com.fasterxml.jackson.databind.JsonNode actualWire = json.valueToTree(actual);
        assertThat(actualWire).isEqualTo(wire);
    }

    @Test void unsupportedHierarchyAndStrictOptInFailClosed() {
        var wire = (com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(new ContractTextAnalysisRequest(project, materials));
        wire.put("semanticHierarchyProtocol", "contract-bounded-hierarchy-v1");
        assertThatThrownBy(() -> json.treeToValue(wire, ContractTextAnalysisRequest.class)).isInstanceOf(Exception.class);
        wire.put("semanticMoneyMode", "OBSERVE").put("semanticHierarchyProtocol", "unapproved-hierarchy");
        assertThatThrownBy(() -> json.treeToValue(wire, ContractTextAnalysisRequest.class)).isInstanceOf(Exception.class);
    }
}
