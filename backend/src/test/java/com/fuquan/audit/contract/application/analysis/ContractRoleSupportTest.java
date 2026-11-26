package com.fuquan.audit.contract.application.analysis;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ContractRoleSupportTest {
    @Test void counterpartyCannotSueDoesNotMeanClientCannotEnforce(){
        String quote="乙方放弃通过诉讼或仲裁救济的权利。";
        assertThat(ContractRoleSupport.supports("导致甲方无法通过法定程序追责",List.of(quote))).isFalse();
        assertThat(ContractRoleSupport.supports("限制乙方的救济，可能影响条款可执行性",List.of(quote))).isTrue();
        assertThat(ContractRoleSupport.supports("甲方无法通过诉讼救济",List.of(quote,"甲方不得提起诉讼。"))).isTrue();
    }
    @Test void compensationRecipientDoesNotBecomeDebtor(){
        String quote="乙方须承担全部赔偿责任，向甲方支付赔偿。";
        assertThat(ContractRoleSupport.supports("甲方可能承担巨额赔偿债务",List.of(quote))).isFalse();
        assertThat(ContractRoleSupport.supports("乙方须承担无上限赔偿，可能影响追责条款可执行性",List.of(quote))).isTrue();
        assertThat(ContractRoleSupport.supports("乙方责任上限过低，甲方可能无法充分追偿",List.of("乙方最高赔偿一元。"))).isTrue();
    }
    @Test void noActorAndMixedObligationsAreNotGuessed(){
        assertThat(ContractRoleSupport.supports("付款触发条件不清楚",List.of("验收完成后付款"))).isTrue();
        assertThat(ContractRoleSupport.supports("甲方需承担赔偿",List.of("甲方应承担赔偿，乙方也应承担赔偿。"))).isTrue();
        assertThat(ContractRoleSupport.supports("甲方无法提起诉讼",List.of("乙方不得阻碍甲方提起诉讼。"))).isTrue();
    }
}
