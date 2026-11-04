package com.fuquan.audit.contract.infra.memory;

import com.fuquan.audit.contract.application.port.ContractRepository;
import com.fuquan.audit.contract.application.reviewrun.port.ContractSnapshotLockPort;
import com.fuquan.audit.contract.domain.Contract;
import com.fuquan.audit.common.observability.FlowDebugLogger;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * local profile 的合同内存仓储适配器。
 *
 * <p>该实现只服务本地开发和自动化测试，不替代正式环境以 DM8 为事实源的合同持久化适配器。</p>
 */
@Repository
@Profile("local")
public class InMemoryContractRepository implements ContractRepository, ContractSnapshotLockPort {

    private final LocalContractStore store;
    private final FlowDebugLogger flowDebugLogger;

    /**
     * 创建带仓储读取可观测性的 local 合同适配器。
     *
     * @param flowDebugLogger 分层流程调试日志记录器
     */
    public InMemoryContractRepository(FlowDebugLogger flowDebugLogger) {
        this(new LocalContractStore(), flowDebugLogger);
    }

    @Autowired
    public InMemoryContractRepository(LocalContractStore store, FlowDebugLogger flowDebugLogger) {
        this.store = store;
        this.flowDebugLogger = flowDebugLogger;
    }

    /**
     * 从固定 local fixture 快照中按 UUID 查找合同。
     *
     * @param id 合同唯一标识
     * @return 找到的合同；不存在时返回空
     */
    @Override
    public Optional<Contract> findById(UUID id) {
        long startedAt = System.nanoTime();
        Contract contract = store.findById(id).orElse(null);
        flowDebugLogger.log(
                "CONTRACT_REPOSITORY_READ", "CONTRACT", "REPOSITORY", "findById",
                Map.of("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                        "outcome", contract == null ? "NOT_FOUND" : "FOUND"));
        return Optional.ofNullable(contract);
    }

    @Override
    public Optional<Contract> findByIdForUpdate(UUID id) {
        return findById(id); // local store is serialized by the run-create coordinator
    }
}
