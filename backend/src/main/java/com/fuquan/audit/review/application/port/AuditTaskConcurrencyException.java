package com.fuquan.audit.review.application.port;

/**
 * 表示审查任务创建或乐观锁状态转换发生并发冲突。
 *
 * <p>应用服务可将该基础设施并发信号转换为稳定的业务冲突响应。</p>
 */
public class AuditTaskConcurrencyException extends RuntimeException {

    /**
     * 创建带诊断信息的任务并发异常。
     *
     * @param message 不包含敏感数据的冲突说明
     */
    public AuditTaskConcurrencyException(String message) {
        super(message);
    }
}
