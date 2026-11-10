package com.fuquan.audit.common.privacy;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClientReviewIdentityTest {
    @Test void javaAndPythonSharePartyVariantConformanceCases() throws Exception {
        Path relative=Path.of("services/audit-ai/tests/fixtures/client_party_cases.json");
        Path root=Path.of("").toAbsolutePath();
        if(!java.nio.file.Files.isRegularFile(root.resolve(relative)))root=root.getParent();
        var data=new com.fasterxml.jackson.databind.ObjectMapper().readTree(root.resolve(relative).toFile());
        var names=new ArrayList<String>();data.path("names").forEach(n->names.add(n.asText()));
        var policy=new ClientReviewIdentity(()->names);
        for(var c:data.path("cases"))assertThat(policy.party(c.path("text").asText()))
            .as(c.path("id").asText()).isEqualTo(c.path("expected").asText());
    }
    private ClientReviewIdentity policy(){return new ClientReviewIdentity(()->List.of("北辰市公安局","警务保障处"));}
    @Test void fullPartyFieldsResolveEitherSideButMentionsAndSubstringsDoNot(){
        var p=policy();
        assertThat(p.party("甲方（采购人）：北辰市公安局\n乙方：远景公司")).isEqualTo("PARTY_A");
        assertThat(p.party("甲方：远景公司\n乙方：警务保障处")).isEqualTo("PARTY_B");
        assertThat(p.party("甲方：北辰市公安局\n乙方：警务保障处")).isEqualTo("AMBIGUOUS");
        assertThat(p.party("甲方：北辰市公安局后勤服务有限公司\n抄送北辰市公安局")).isEqualTo("UNMATCHED");
        assertThat(p.party("项目名称：北辰市公安局采购\n甲方：其他单位")).isEqualTo("UNMATCHED");
        assertThat(p.party("甲方（盖章）：北 辰 市 公 安 局")).isEqualTo("PARTY_A");
    }
    @Test void boundedFragmentsReceiveFullDocumentPartyWithoutIdentityNames(){
        var id=UUID.randomUUID();var roles=policy().parties(Map.of(id,"乙方：警务保障处\n付款安排"),List.of(id));
        assertThat(roles).containsEntry(id,"PARTY_B");
        assertThat(roles.toString()).doesNotContain("警务保障处");
    }
    @Test void policyIsImmutableWithinOneProcessAndNamesStayInInternalHeader(){
        var names=new ArrayList<>(List.of("北辰市公安局"));var p=new ClientReviewIdentity(()->names);
        String header=p.internalHeader();names.clear();
        assertThat(p.party("甲方：北辰市公安局")).isEqualTo("PARTY_A");
        assertThat(new String(Base64.getDecoder().decode(header),StandardCharsets.UTF_8)).contains("北辰市公安局");
    }
    @Test void missingConfigurationFailsClosedAndDoesNotInventClient(){
        assertThatThrownBy(()->new ClientReviewIdentity(List::of).internalHeader())
            .isInstanceOf(IllegalStateException.class).hasMessage("CLIENT_IDENTITY_NOT_CONFIGURED");
        assertThat(policy().party("甲方：其他单位")).isEqualTo("UNMATCHED");
    }
}
