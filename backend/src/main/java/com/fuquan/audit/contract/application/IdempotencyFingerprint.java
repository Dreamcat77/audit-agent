package com.fuquan.audit.contract.application;

import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 为 F02 写命令生成稳定的幂等请求指纹。
 *
 * <p>指纹绑定操作者、合同版本、审查类型和已启用规则版本，防止同一幂等键被复用于不同业务输入。</p>
 */
public final class IdempotencyFingerprint {

    /**
     * 禁止实例化仅提供指纹算法的工具类。
     */
    private IdempotencyFingerprint() {
    }

    /**
     * 生成 F02 命令规范输入的 SHA-256 指纹。
     *
     * @param scope 幂等业务范围
     * @param actorId 发起审查的用户标识
     * @param contract 待审查合同及其版本
     * @param configurations 本次执行采用的规则配置版本集合
     * @return 小写十六进制 SHA-256 指纹
     * @throws IllegalStateException Java 17 运行环境异常缺少 SHA-256 算法时抛出
     */
    public static String forF02(
            String scope,
            String actorId,
            Contract contract,
            List<ContractRuleConfiguration> configurations) {
        String ruleVersions = configurations.stream()
                .sorted(java.util.Comparator.comparing(ContractRuleConfiguration::ruleCode))
                .map(config -> config.ruleCode() + ":" + config.versionNo())
                .collect(java.util.stream.Collectors.joining(","));
        String canonical = String.join("\n",
                scope,
                actorId,
                contract.id().toString(),
                Long.toString(contract.versionNo()),
                ContractReviewService.REVIEW_TYPE,
                ruleVersions);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java 17", exception);
        }
    }
}
