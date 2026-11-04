package com.fuquan.audit.procurement.application;
import static org.assertj.core.api.Assertions.*;
import com.fuquan.audit.procurement.domain.ProcurementTenderRule;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
class TenderRuleNegationTest {
 private final ProcurementTenderRule rule=new ProcurementTenderRule(UUID.randomUUID(),"TEST-BRAND","品牌条件","品牌","KEYWORD","指定品牌|必须使用星桥品牌","CRITICAL","按性能采购","BUILTIN",true,Instant.EPOCH,Instant.EPOCH);
 @Test void fairnessProhibitionsAreNotDiscriminatoryRules() {
  for(String text:new String[]{"不得指定品牌。","不应指定品牌。","本项目不指定品牌。","禁止要求指定品牌。"})
   assertThat(TenderRuleMatcher.hasAffirmativeMatch(rule,text)).as(text).isFalse();
 }
 @Test void adverseConditionAfterCounterevidenceAndDoubleNegationAreRetained() {
  for(String text:new String[]{"本项目不指定品牌，但设备必须使用星桥品牌。","不得指定品牌。设备必须使用星桥品牌。","设备必须使用星桥品牌。","实际不得不指定品牌。"})
   assertThat(TenderRuleMatcher.hasAffirmativeMatch(rule,text)).as(text).isTrue();
 }
 @Test void explicitlyForbiddenQuotedExampleIsNotAnActualQualification() {
  assertThat(TenderRuleMatcher.hasAffirmativeMatch(rule,
    "编制说明：以下引号内容是禁止采用的反面示例：“设备必须使用星桥品牌”。该示例不是本项目的资格条件。")) .isFalse();
  var region=new ProcurementTenderRule(UUID.randomUUID(),"TEST-REGION","注册条件","地域","KEYWORD","本地注册","WARNING","公平采购","BUILTIN",true,Instant.EPOCH,Instant.EPOCH);
  assertThat(TenderRuleMatcher.hasAffirmativeMatch(region,
    "编制说明：以下引号内容是禁止采用的反面示例：“仅限本地注册企业参与投标”。该示例不是本项目的资格条件，本项目不得采用此限制。")) .isFalse();
  assertThat(TenderRuleMatcher.hasAffirmativeMatch(region,
    "禁止采用的反面示例：“仅限本地注册企业”，但本项目须由本地注册企业参与。")) .isTrue();
 }
 @Test void realRestrictionOutsideForbiddenQuoteRemainsVisible() {
  for(String text:new String[]{
    "禁止采用的反面示例：“指定品牌”。本项目设备必须使用星桥品牌。",
    "禁止采用的反面示例：“指定品牌”，但设备必须使用星桥品牌。",
    "资格条件示例：“设备必须使用星桥品牌”。",
    "本项目必须使用星桥品牌。下一段是禁止采用的反面示例：“指定品牌”。"})
    assertThat(TenderRuleMatcher.hasAffirmativeMatch(rule,text)).as(text).isTrue();
 }
}
