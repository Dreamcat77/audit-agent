package com.fuquan.audit.common.material;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class SourceIdentityPolicyTest {
    @Test void mergeFieldsAndRoleCaptionsAreNotNamesButRealOrganizationsNeedNoCompanySuffix(){
        for(String value:List.of("[合同中心-供应商名称]","【采购中心-项目名称】","${supplierName}","{{乙方单位名称}}","供应商名称","projectName","甲方：甲方"))
            assertThat(SourceIdentityPolicy.plausible("supplierName",value)).as(value).isFalse();
        for(String value:List.of("晨星信息中心","张三","ACME Ltd.","晨星市公安局（主管）","远景合伙企业","晨星[华东]分公司"))
            assertThat(SourceIdentityPolicy.plausible("supplierName",value)).as(value).isTrue();
        assertThat(SourceIdentityPolicy.plausible("supplierName","123456789")).isFalse();
        assertThat(SourceIdentityPolicy.plausible("projectNo","XM-001")).isTrue();
        assertThat(SourceIdentityPolicy.plausible("budgetAmount","2200000元")).isTrue();
    }
    @Test void adjacentLabelsDisambiguateTwoNumbersAndTwoActorsInOneExcerpt(){
        String text="项目编号：XM-01；合同统一编号：HT-09；采购人：远景单位；采购代理机构：晨星中心";
        assertThat(SourceIdentityPolicy.role("XM-01",text)).isEqualTo("projectNo");
        assertThat(SourceIdentityPolicy.role("HT-09",text)).isEqualTo("contractNo");
        assertThat(SourceIdentityPolicy.role("远景单位",text)).isEqualTo("purchaserName");
        assertThat(SourceIdentityPolicy.role("晨星中心",text)).isEqualTo("agencyName");
        assertThat(SourceIdentityPolicy.role("913456","统一社会信用代码：913456")).isEqualTo("creditCode");
        assertThat(SourceIdentityPolicy.role("ZB-01","招标编号：ZB-01")).isEqualTo("tenderNo");
    }
    @Test void identityFormattingAndApprovedAliasesMergeButSimilarCompaniesDoNot(){
        assertThat(SourceIdentityPolicy.identityKey("北辰市公安局（主管）")).isEqualTo(SourceIdentityPolicy.identityKey("北 辰 市 公 安 局"));
        assertThat(SourceIdentityPolicy.identityKey("晨星软件公司")).isNotEqualTo(SourceIdentityPolicy.identityKey("晨星服务公司"));
        assertThat(SourceIdentityPolicy.aliases(List.of("采购人：北辰市公安局（以下简称“本局”）"))).containsEntry("本局","北辰市公安局");
        assertThat(SourceIdentityPolicy.placeholder("乙方（承揽方）")).isTrue();
        assertThat(SourceIdentityPolicy.role("机构甲","采购人：机构乙，机构甲")).isNull();
    }
}
