package com.fuquan.audit.contract.application.port;

import com.fuquan.audit.contract.domain.ContractRuleConfiguration;
import java.util.List;

/**
 * F02 规则配置的应用层读取端口。
 *
 * <p>端口返回持久化配置及版本，领域规则实现与 DM8 或 local 内存适配器保持解耦。</p>
 */
public interface ContractRuleConfigRepository {

    /**
     * 读取当前启用的 F02 规则配置。
     *
     * @return 启用规则配置列表；应用层会再次过滤并按规则编码排序
     */
    List<ContractRuleConfiguration> findEnabledForF02();
}
