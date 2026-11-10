package com.fuquan.audit.contract.domain;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
class ContractPartyNameTest {
    @Test void roleCaptionsAndEmptyPositionsAreNotNames() {
        for(String name:List.of("乙方（承揽方）","甲方（定作方）（盖章）","（承揽方）","承揽方","乙方：_____","乙方 (供应商)：","Party B","无","待填写","住所：法定代表人：电话：","乙方（盖章）：住所：电话："))
            assertThat(ContractPartyName.isPresent(name)).as(name).isFalse();
    }
    @Test void actualNamesIncludingNaturalPersonsAndNamesContainingRolesAreKept() {
        for(String name:List.of("青禾大学","北岭家具有限公司","张三","乙方科技有限公司","甲方（青禾）技术有限公司"))
            assertThat(ContractPartyName.isPresent(name)).as(name).isTrue();
    }
}
