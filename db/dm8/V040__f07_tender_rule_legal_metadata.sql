-- V040: F07 规则库补充法规依据和规则说明。

ALTER TABLE PROCUREMENT_TENDER_RULE
    ADD LEGAL_BASIS VARCHAR(1000) DEFAULT '相关政府采购法律法规及规范性文件' NOT NULL;

ALTER TABLE PROCUREMENT_TENDER_RULE
    ADD RULE_EXPLANATION VARCHAR(2000) DEFAULT '该规则用于识别可能影响公平竞争或供应商合理履约的招标条件，需要结合项目实际情况人工复核。' NOT NULL;

UPDATE PROCUREMENT_TENDER_RULE
SET LEGAL_BASIS='《政府采购法》第22条、《政府采购法实施条例》第20条',
    RULE_EXPLANATION='指定品牌、品牌授权或品牌兼容要求会直接排斥其他品牌供应商，可能违反公平竞争原则，应改用客观的功能和性能参数。'
WHERE RISK_CATEGORY='指定品牌';

UPDATE PROCUREMENT_TENDER_RULE
SET LEGAL_BASIS='《政府采购法》第22条、《优化营商环境条例》第14条',
    RULE_EXPLANATION='要求本地企业、本地注册、本地纳税或本地服务，可能构成地域歧视，排斥符合条件的外地供应商。'
WHERE RISK_CATEGORY='地域限制';

UPDATE PROCUREMENT_TENDER_RULE
SET LEGAL_BASIS='《政府采购法》第22条、《招标投标法》第18条',
    RULE_EXPLANATION='不接受联合体、只允许原厂或唯一代理等条件会不当缩小竞争范围，除非有充分、必要且可说明的项目理由。'
WHERE RISK_CATEGORY='排他性条件';

UPDATE PROCUREMENT_TENDER_RULE
SET LEGAL_BASIS='《政府采购法》第22条、《政府采购促进中小企业发展管理办法》',
    RULE_EXPLANATION='注册资本、认证、资质和业绩等要求应与项目规模及实际履约需要匹配，过高门槛可能排斥中小企业。'
WHERE RISK_CATEGORY='资质门槛';

UPDATE PROCUREMENT_TENDER_RULE
SET LEGAL_BASIS='《政府采购法实施条例》第20条',
    RULE_EXPLANATION='技术参数应采用客观、功能性且可验证的描述，不应指向特定品牌、产品、系统或协议。'
WHERE RISK_CATEGORY='技术参数倾向';

UPDATE PROCUREMENT_TENDER_RULE
SET LEGAL_BASIS='《政府采购货物和服务招标投标管理办法》第55条',
    RULE_EXPLANATION='评分项应客观、量化、明确，主观分比例过高或标准模糊会扩大评审自由裁量，影响公平竞争。'
WHERE RISK_CATEGORY='评分标准倾向';

UPDATE PROCUREMENT_TENDER_RULE
SET LEGAL_BASIS='《保障中小企业款项支付条例》',
    RULE_EXPLANATION='付款、预付款和质保金等条件应符合行业惯例及中小企业款项支付要求，过苛条件可能加重供应商负担。'
WHERE RISK_CATEGORY='付款条件';

UPDATE PROCUREMENT_TENDER_RULE
SET LEGAL_BASIS='《招标投标法》第19条',
    RULE_EXPLANATION='工期、响应和交付期限应基于项目实际需要及履约能力合理确定，明显过短可能限制竞争。'
WHERE RISK_CATEGORY='工期要求';

UPDATE PROCUREMENT_TENDER_RULE
SET LEGAL_BASIS='《政府采购法》第22条',
    RULE_EXPLANATION='售后、驻场和响应时限应与项目实际服务需要匹配，过高要求可能增加不必要负担或形成地域限制。'
WHERE RISK_CATEGORY='售后服务';

COMMENT ON COLUMN PROCUREMENT_TENDER_RULE.LEGAL_BASIS IS '规则适用的法规依据；用于向业务人员解释风险来源。';
COMMENT ON COLUMN PROCUREMENT_TENDER_RULE.RULE_EXPLANATION IS '规则识别的合规风险说明；用于辅助人工判断，不替代法律意见。';

COMMIT;
