package com.fuquan.audit.procurement.application;

import com.fuquan.audit.procurement.domain.ProcurementTenderRule;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** F07 内置规则目录；内置规则通过迁移初始化，来源标记为 BUILTIN。 */
public final class TenderRuleCatalog {
    private static final String BRAND = "指定品牌";
    private static final String REGION = "地域限制";
    private static final String EXCLUSIVE = "排他性条件";
    private static final String QUALIFICATION = "资质门槛";
    private static final String TECHNICAL = "技术参数倾向";
    private static final String SCORE = "评分标准倾向";
    private static final String PAYMENT = "付款条件";
    private static final String DURATION = "工期要求";
    private static final String SERVICE = "售后服务";
    private static final String TEXT_WITHOUT_PUNCTUATION = "[^，。；：！？,.!?]";

    private TenderRuleCatalog() {}

    public static List<ProcurementTenderRule> defaults(Instant now) {
        return List.of(
                // 指定品牌（8）
                rule("F07-BRAND-001", "指定品牌", BRAND, "KEYWORD", "指定品牌", "CRITICAL",
                        "建议删除品牌指定，改为性能参数要求", now),
                rule("F07-BRAND-002", "必须使用指定品牌", BRAND, "REGEX", between("必须使用", 20, "品牌"),
                        "CRITICAL", "建议删除品牌指定，改为性能参数要求", now),
                rule("F07-BRAND-003", "品牌授权要求", BRAND, "REGEX",
                        TEXT_WITHOUT_PUNCTUATION + "{0,20}?品牌" + TEXT_WITHOUT_PUNCTUATION
                                + "{0,5}?授权",
                        "CRITICAL", "建议删除品牌指定，改为性能参数要求", now),
                rule("F07-BRAND-004", "品牌产品要求", BRAND, "REGEX", between("", 20, "品牌产品"),
                        "CRITICAL", "建议删除品牌指定，改为性能参数要求", now),
                rule("F07-BRAND-005", "仅限指定品牌", BRAND, "REGEX", between("仅限", 20, "品牌"),
                        "CRITICAL", "建议删除品牌指定，改为性能参数要求", now),
                rule("F07-BRAND-006", "建议采用指定品牌", BRAND, "REGEX", between("建议采用", 20, "品牌"),
                        "WARNING", "建议删除品牌指定，改为性能参数要求", now),
                rule("F07-BRAND-007", "品牌兼容性限制", BRAND, "REGEX", between("必须兼容", 20, "品牌"),
                        "WARNING", "建议删除品牌指定，改为性能参数要求", now),
                rule("F07-BRAND-008", "现有系统同品牌限制", BRAND, "KEYWORD", "与现有系统同品牌",
                        "WARNING", "建议删除品牌指定，改为性能参数要求", now),

                // 地域限制（8）；F07-REGION-001 要求出现地域限制语境，避免命中“本市财政局”等机构名称。
                rule("F07-REGION-001", "本市地域限制", REGION, "REGEX",
                        "本市(?=企业|注册|设有|服务|纳税|社保|供应商|投标人)", "WARNING",
                        "建议删除地域限制，允许外地企业参与", now),
                rule("F07-REGION-002", "本地企业限制", REGION, "KEYWORD", "本地企业", "WARNING",
                        "建议删除地域限制，允许外地企业参与", now),
                rule("F07-REGION-003", "本地设有要求", REGION, "KEYWORD", "本地设有", "WARNING",
                        "建议删除地域限制，允许外地企业参与", now),
                rule("F07-REGION-004", "本地注册要求", REGION, "KEYWORD", "本地注册", "WARNING",
                        "建议删除地域限制，允许外地企业参与", now),
                rule("F07-REGION-005", "本地服务要求", REGION, "KEYWORD", "本地服务", "WARNING",
                        "建议删除地域限制，允许外地企业参与", now),
                rule("F07-REGION-006", "本地纳税要求", REGION, "KEYWORD", "本地纳税", "WARNING",
                        "建议删除地域限制，允许外地企业参与", now),
                rule("F07-REGION-007", "本地社保要求", REGION, "KEYWORD", "本地社保", "WARNING",
                        "建议删除地域限制，允许外地企业参与", now),
                rule("F07-REGION-008", "注册地限定区域", REGION, "REGEX",
                        between("注册地在", 10, "(?:市|区|县)"), "WARNING",
                        "建议删除地域限制，允许外地企业参与", now),

                // 排他性条件（8）
                rule("F07-EXCLUSIVE-001", "不接受联合体", EXCLUSIVE, "KEYWORD", "不接受联合体", "WARNING",
                        "建议删除排他性条件，保证公平竞争", now),
                rule("F07-EXCLUSIVE-002", "独家授权", EXCLUSIVE, "KEYWORD", "独家授权", "CRITICAL",
                        "建议删除排他性条件，保证公平竞争", now),
                rule("F07-EXCLUSIVE-003", "唯一代理", EXCLUSIVE, "KEYWORD", "唯一代理", "CRITICAL",
                        "建议删除排他性条件，保证公平竞争", now),
                rule("F07-EXCLUSIVE-004", "唯一供应商", EXCLUSIVE, "KEYWORD", "唯一供应商", "CRITICAL",
                        "建议删除排他性条件，保证公平竞争", now),
                rule("F07-EXCLUSIVE-005", "不接受分包", EXCLUSIVE, "KEYWORD", "不接受分包", "WARNING",
                        "建议删除排他性条件，保证公平竞争", now),
                rule("F07-EXCLUSIVE-006", "原厂投标限制", EXCLUSIVE, "KEYWORD", "必须为原厂", "CRITICAL",
                        "建议删除排他性条件，保证公平竞争", now),
                rule("F07-EXCLUSIVE-007", "厂家直投限制", EXCLUSIVE, "KEYWORD", "必须为厂家直投", "CRITICAL",
                        "建议删除排他性条件，保证公平竞争", now),
                rule("F07-EXCLUSIVE-008", "代理商投标限制", EXCLUSIVE, "KEYWORD", "不接受代理商投标", "CRITICAL",
                        "建议删除排他性条件，保证公平竞争", now),

                // 资质门槛（8）
                rule("F07-QUAL-001", "注册资本门槛", QUALIFICATION, "REGEX",
                        between("注册资本不低于", 10, "万"), "WARNING",
                        "建议核实资质要求是否与项目规模匹配", now),
                rule("F07-QUAL-002", "认证要求", QUALIFICATION, "REGEX",
                        between("必须具有", 30, "认证"), "WARNING",
                        "建议核实资质要求是否与项目规模匹配", now),
                rule("F07-QUAL-003", "资质要求", QUALIFICATION, "REGEX",
                        between("必须具有", 30, "资质"), "WARNING",
                        "建议核实资质要求是否与项目规模匹配", now),
                rule("F07-QUAL-004", "限定年限业绩要求", QUALIFICATION, "REGEX",
                        "近" + TEXT_WITHOUT_PUNCTUATION + "{0,5}?年具有" + TEXT_WITHOUT_PUNCTUATION
                                + "{0,30}?业绩",
                        "WARNING", "建议核实资质要求是否与项目规模匹配", now),
                rule("F07-QUAL-005", "行业经验年限要求", QUALIFICATION, "REGEX",
                        "具有" + TEXT_WITHOUT_PUNCTUATION + "{0,20}?行业经验" + TEXT_WITHOUT_PUNCTUATION
                                + "{0,5}?年以上",
                        "WARNING", "建议核实资质要求是否与项目规模匹配", now),
                rule("F07-QUAL-006", "证书要求", QUALIFICATION, "REGEX",
                        between("必须具有", 20, "证书"), "WARNING",
                        "建议核实资质要求是否与项目规模匹配", now),
                rule("F07-QUAL-007", "认证通过要求", QUALIFICATION, "REGEX",
                        between("必须通过", 20, "认证"), "WARNING",
                        "建议核实资质要求是否与项目规模匹配", now),
                rule("F07-QUAL-008", "等级资质要求", QUALIFICATION, "REGEX",
                        between("具有", 20, "等级资质"), "WARNING",
                        "建议核实资质要求是否与项目规模匹配", now),

                // 技术参数倾向（8）
                rule("F07-TECH-001", "指定系列参数", TECHNICAL, "REGEX",
                        between("必须采用", 30, "系列"), "WARNING",
                        "建议改为功能性描述，避免指向特定产品", now),
                rule("F07-TECH-002", "指定系统支持", TECHNICAL, "REGEX",
                        between("必须支持", 30, "系统"), "WARNING",
                        "建议改为功能性描述，避免指向特定产品", now),
                rule("F07-TECH-003", "指定产品使用", TECHNICAL, "REGEX",
                        between("必须使用", 30, "产品"), "WARNING",
                        "建议改为功能性描述，避免指向特定产品", now),
                rule("F07-TECH-004", "指定系统兼容", TECHNICAL, "REGEX",
                        between("必须兼容", 20, "系统"), "WARNING",
                        "建议改为功能性描述，避免指向特定产品", now),
                rule("F07-TECH-005", "技术方案一致性限制", TECHNICAL, "REGEX",
                        between("必须与", 20, "一致"), "WARNING",
                        "建议改为功能性描述，避免指向特定产品", now),
                rule("F07-TECH-006", "特定技术参数", TECHNICAL, "REGEX",
                        between("特定", 20, "技术参数"), "WARNING",
                        "建议改为功能性描述，避免指向特定产品", now),
                rule("F07-TECH-007", "指定标准参数", TECHNICAL, "REGEX",
                        between("必须满足", 30, "标准"), "WARNING",
                        "建议改为功能性描述，避免指向特定产品", now),
                rule("F07-TECH-008", "指定协议限制", TECHNICAL, "REGEX",
                        between("仅支持", 20, "协议"), "WARNING",
                        "建议改为功能性描述，避免指向特定产品", now),

                // 评分标准倾向（6）
                rule("F07-SCORE-001", "主观分比例过高", SCORE, "REGEX",
                        between("主观分占比", 10, "(?:%|％)以上"), "WARNING",
                        "建议量化评分标准，减少主观分", now),
                rule("F07-SCORE-002", "评分标准不明确", SCORE, "KEYWORD", "评分标准不明确", "WARNING",
                        "建议量化评分标准，减少主观分", now),
                rule("F07-SCORE-003", "评委主观打分", SCORE, "KEYWORD", "由评委主观打分", "WARNING",
                        "建议量化评分标准，减少主观分", now),
                rule("F07-SCORE-004", "特定专家打分", SCORE, "KEYWORD", "特定专家打分", "WARNING",
                        "建议量化评分标准，减少主观分", now),
                rule("F07-SCORE-005", "评分标准模糊", SCORE, "KEYWORD", "评分标准模糊", "WARNING",
                        "建议量化评分标准，减少主观分", now),
                rule("F07-SCORE-006", "主观评分比例过高", SCORE, "REGEX",
                        between("主观评分", 10, "(?:分以上|分及以上)"), "WARNING",
                        "建议量化评分标准，减少主观分", now),

                // 付款条件（6）
                rule("F07-PAYMENT-001", "预付款比例过高", PAYMENT, "REGEX",
                        between("预付款比例", 10, "(?:%|％)以上"), "WARNING",
                        "建议调整付款条件，符合行业惯例", now),
                rule("F07-PAYMENT-002", "质保金比例限制", PAYMENT, "REGEX",
                        between("质保金比例", 10, "(?:%|％)以下"), "WARNING",
                        "建议调整付款条件，符合行业惯例", now),
                rule("F07-PAYMENT-003", "付款周期过长", PAYMENT, "REGEX",
                        between("付款周期", 10, "天以上"), "WARNING",
                        "建议调整付款条件，符合行业惯例", now),
                rule("F07-PAYMENT-004", "验收后付款周期过长", PAYMENT, "REGEX",
                        between("验收后", 10, "年内付款"), "WARNING",
                        "建议调整付款条件，符合行业惯例", now),
                rule("F07-PAYMENT-005", "无预付款", PAYMENT, "KEYWORD", "无预付款", "INFO",
                        "建议调整付款条件，符合行业惯例", now),
                rule("F07-PAYMENT-006", "付款条件苛刻", PAYMENT, "KEYWORD", "付款条件苛刻", "WARNING",
                        "建议调整付款条件，符合行业惯例", now),

                // 工期要求（5）
                rule("F07-DURATION-001", "工期过短", DURATION, "REGEX",
                        between("工期", 10, "天以内"), "WARNING", "建议核实工期合理性", now),
                rule("F07-DURATION-002", "响应时限过短", DURATION, "REGEX",
                        between("必须", 5, "小时内响应"), "WARNING", "建议核实工期合理性", now),
                rule("F07-DURATION-003", "完成时限过短", DURATION, "REGEX",
                        between("必须在", 5, "天内完成"), "WARNING", "建议核实工期合理性", now),
                rule("F07-DURATION-004", "工期明显短于常规", DURATION, "KEYWORD", "工期明显短于", "WARNING",
                        "建议核实工期合理性", now),
                rule("F07-DURATION-005", "紧急工期", DURATION, "KEYWORD", "紧急工期", "WARNING",
                        "建议核实工期合理性", now),

                // 售后服务（5）
                rule("F07-SERVICE-001", "本地服务团队要求", SERVICE, "KEYWORD", "要求本地服务团队", "WARNING",
                        "建议调整为合理的服务要求", now),
                rule("F07-SERVICE-002", "服务响应时间过短", SERVICE, "REGEX",
                        between("响应时间", 5, "小时以内"), "WARNING", "建议调整为合理的服务要求", now),
                rule("F07-SERVICE-003", "服务期限过长", SERVICE, "REGEX",
                        between("服务期限", 5, "年以上"), "WARNING", "建议调整为合理的服务要求", now),
                rule("F07-SERVICE-004", "驻场服务要求", SERVICE, "KEYWORD", "必须提供驻场服务", "WARNING",
                        "建议调整为合理的服务要求", now),
                rule("F07-SERVICE-005", "全天候服务要求", SERVICE, "KEYWORD", "7×24小时服务", "INFO",
                        "建议调整为合理的服务要求", now));
    }

    private static String between(String prefix, int maxLength, String suffix) {
        return prefix + TEXT_WITHOUT_PUNCTUATION + "{0," + maxLength + "}?" + suffix;
    }

    private static ProcurementTenderRule rule(String code, String name, String category, String type,
            String content, String level, String suggestion, Instant now) {
        return new ProcurementTenderRule(UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8)),
                code, name, category, type, content, level, suggestion,
                legalBasis(category), ruleExplanation(category), "BUILTIN", true, now, now);
    }

    private static String legalBasis(String category) {
        return switch (category) {
            case BRAND -> "《政府采购法》第22条、《政府采购法实施条例》第20条";
            case REGION -> "《政府采购法》第22条、《优化营商环境条例》第14条";
            case EXCLUSIVE -> "《政府采购法》第22条、《招标投标法》第18条";
            case QUALIFICATION -> "《政府采购法》第22条、《政府采购促进中小企业发展管理办法》";
            case TECHNICAL -> "《政府采购法实施条例》第20条";
            case SCORE -> "《政府采购货物和服务招标投标管理办法》第55条";
            case PAYMENT -> "《保障中小企业款项支付条例》";
            case DURATION -> "《招标投标法》第19条";
            case SERVICE -> "《政府采购法》第22条";
            default -> "相关政府采购法律法规及规范性文件";
        };
    }

    private static String ruleExplanation(String category) {
        return switch (category) {
            case BRAND -> "指定品牌、品牌授权或品牌兼容要求会直接排斥其他品牌供应商，可能违反公平竞争原则，应改用客观的功能和性能参数。";
            case REGION -> "要求本地企业、本地注册、本地纳税或本地服务，可能构成地域歧视，排斥符合条件的外地供应商。";
            case EXCLUSIVE -> "不接受联合体、只允许原厂或唯一代理等条件会不当缩小竞争范围，除非有充分、必要且可说明的项目理由。";
            case QUALIFICATION -> "注册资本、认证、资质和业绩等要求应与项目规模及实际履约需要匹配，过高门槛可能排斥中小企业。";
            case TECHNICAL -> "技术参数应采用客观、功能性且可验证的描述，不应指向特定品牌、产品、系统或协议。";
            case SCORE -> "评分项应客观、量化、明确，主观分比例过高或标准模糊会扩大评审自由裁量，影响公平竞争。";
            case PAYMENT -> "付款、预付款和质保金等条件应符合行业惯例及中小企业款项支付要求，过苛条件可能加重供应商负担。";
            case DURATION -> "工期、响应和交付期限应基于项目实际需要及履约能力合理确定，明显过短可能限制竞争。";
            case SERVICE -> "售后、驻场和响应时限应与项目实际服务需要匹配，过高要求可能增加不必要负担或形成地域限制。";
            default -> "该规则用于识别可能影响公平竞争或供应商合理履约的招标条件，需要结合项目实际情况人工复核。";
        };
    }
}
