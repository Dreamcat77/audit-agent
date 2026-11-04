package com.fuquan.audit.contract.application.port;

import com.fuquan.audit.contract.domain.Contract;
import java.util.Optional;
import java.util.UUID;

/**
 * 合同聚合的应用层读取端口。
 *
 * <p>正式环境适配器以 DM8 为合同事实源；local profile 可使用内存实现，但应用层不感知存储产品。</p>
 */
public interface ContractRepository {

    /**
     * 按唯一标识读取一次审查所需的完整合同快照。
     *
     * @param id 合同唯一标识
     * @return 找到的合同；不存在时返回空
     */
    Optional<Contract> findById(UUID id);
}
