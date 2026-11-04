-- V001：建立最小审核任务生命周期基线，供后续迁移在稳定主键上逐步扩展任务来源、审查类型和审计字段。
-- 此阶段刻意只保存任务标识与生命周期状态，不提前引入后续 F02 所需字段。

-- 审核任务基表；代表系统中一次可独立追踪的审核执行。
CREATE TABLE AUDIT_TASK (
    -- 审核任务唯一标识；作为后续检查结果、操作审计和幂等结果关联任务的稳定主键。
    ID VARCHAR(36) NOT NULL,
    -- 任务生命周期状态；记录审核任务从创建、处理中到完成或失败的当前阶段。
    STATUS VARCHAR(32) NOT NULL,
    -- 保证每个审核任务标识在任务生命周期内唯一，并为后续迁移建立引用基础。
    CONSTRAINT PK_AUDIT_TASK PRIMARY KEY (ID)
);

COMMENT ON TABLE AUDIT_TASK IS '审核任务基表；代表系统中一次可独立追踪的审核执行，并作为后续任务模型演进的稳定基线。';
COMMENT ON COLUMN AUDIT_TASK.ID IS '审核任务唯一标识；作为检查结果、操作审计和幂等结果关联任务的稳定主键。';
COMMENT ON COLUMN AUDIT_TASK.STATUS IS '任务生命周期状态；记录审核任务从创建、处理中到完成或失败的当前阶段。';
