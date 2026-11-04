-- V002：在 V001 审核任务基线之上，建立系统自有账号、角色、安全事件和长期业务操作审计模型。
-- DM8 保存账号及审计事实；Session Cookie + CSRF 的运行时会话管理与本迁移的数据职责相互分离。

-- 系统自有登录账号；保存认证、安全状态和登录控制数据，不保存任何明文密码。
CREATE TABLE SYS_USER (
    -- 用户唯一标识；供角色关联、安全审计和业务操作审计稳定引用。
    ID VARCHAR(36) NOT NULL,
    -- 登录用户名；数据库唯一约束防止不同账号共享同一认证标识。
    USERNAME VARCHAR(128) NOT NULL,
    -- 密码编码后的哈希结果；只能保存安全密码编码器输出，不得保存或还原明文密码。
    PASSWORD_HASH VARCHAR(255) NOT NULL,
    -- 用户真实姓名或展示名称；用于界面展示及审计记录识别操作人。
    REAL_NAME VARCHAR(128) NOT NULL,
    -- 账号安全状态：ACTIVE 可认证，LOCKED 临时锁定，DISABLED 停用。
    STATUS VARCHAR(16) NOT NULL,
    -- 连续认证失败次数；用于达到安全阈值后触发账号锁定。
    FAILED_LOGIN_COUNT INTEGER DEFAULT 0 NOT NULL,
    -- 临时锁定截止时间；为空表示当前没有基于时间的登录锁定期限。
    LOCKED_UNTIL TIMESTAMP NULL,
    -- 最近一次成功登录时间；用于安全审计和异常登录排查。
    LAST_LOGIN_AT TIMESTAMP NULL,
    -- 密码最近变更时间；为密码生命周期管理和审计追溯提供依据。
    PASSWORD_CHANGED_AT TIMESTAMP NOT NULL,
    -- 账号记录创建时间。
    CREATED_AT TIMESTAMP NOT NULL,
    -- 账号认证或资料状态最近更新时间。
    UPDATED_AT TIMESTAMP NOT NULL,
    -- 乐观并发版本号；用于账号安全状态更新时识别并发覆盖风险。
    VERSION_NO BIGINT DEFAULT 0 NOT NULL,
    -- 用户表主键，保证账号记录可被稳定引用。
    CONSTRAINT PK_SYS_USER PRIMARY KEY (ID),
    -- 保证登录用户名全局唯一，避免认证身份歧义。
    CONSTRAINT UK_SYS_USER_USERNAME UNIQUE (USERNAME),
    -- 将账号状态限制在认证流程明确支持的三种状态内。
    CONSTRAINT CK_SYS_USER_STATUS CHECK (STATUS IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    -- 防止失败计数出现无业务意义的负值。
    CONSTRAINT CK_SYS_USER_FAILED_COUNT CHECK (FAILED_LOGIN_COUNT >= 0)
);

COMMENT ON TABLE SYS_USER IS '系统自有登录账号；保存认证、安全状态和登录控制数据，不保存任何明文密码。';
COMMENT ON COLUMN SYS_USER.ID IS '用户唯一标识；供角色关联、安全审计和业务操作审计稳定引用。';
COMMENT ON COLUMN SYS_USER.USERNAME IS '登录用户名；数据库唯一约束防止不同账号共享同一认证标识。';
COMMENT ON COLUMN SYS_USER.PASSWORD_HASH IS '密码编码后的哈希结果；只能保存安全密码编码器输出，不得保存或还原明文密码。';
COMMENT ON COLUMN SYS_USER.REAL_NAME IS '用户真实姓名或展示名称；用于界面展示及审计记录识别操作人。';
COMMENT ON COLUMN SYS_USER.STATUS IS '账号安全状态：ACTIVE 可认证，LOCKED 临时锁定，DISABLED 停用。';
COMMENT ON COLUMN SYS_USER.FAILED_LOGIN_COUNT IS '连续认证失败次数；用于达到安全阈值后触发账号锁定。';
COMMENT ON COLUMN SYS_USER.LOCKED_UNTIL IS '临时锁定截止时间；为空表示当前没有基于时间的登录锁定期限。';
COMMENT ON COLUMN SYS_USER.LAST_LOGIN_AT IS '最近一次成功登录时间；用于安全审计和异常登录排查。';
COMMENT ON COLUMN SYS_USER.PASSWORD_CHANGED_AT IS '密码最近变更时间；为密码生命周期管理和审计追溯提供依据。';
COMMENT ON COLUMN SYS_USER.CREATED_AT IS '账号记录创建时间。';
COMMENT ON COLUMN SYS_USER.UPDATED_AT IS '账号认证或资料状态最近更新时间。';
COMMENT ON COLUMN SYS_USER.VERSION_NO IS '乐观并发版本号；用于账号安全状态更新时识别并发覆盖风险。';

-- 系统角色定义；角色代码作为授权配置和 Java 安全表达式之间的稳定业务契约。
CREATE TABLE SYS_ROLE (
    -- 角色唯一标识；供用户角色关联表引用。
    ID VARCHAR(36) NOT NULL,
    -- 稳定角色业务代码；用于授权判断，不应随展示名称变化。
    ROLE_CODE VARCHAR(64) NOT NULL,
    -- 面向管理界面的角色展示名称。
    ROLE_NAME VARCHAR(128) NOT NULL,
    -- 角色启停状态；DISABLED 角色不应继续提供有效授权。
    STATUS VARCHAR(16) NOT NULL,
    -- 角色定义创建时间。
    CREATED_AT TIMESTAMP NOT NULL,
    -- 角色名称或状态最近更新时间。
    UPDATED_AT TIMESTAMP NOT NULL,
    -- 角色表主键，保证角色记录唯一。
    CONSTRAINT PK_SYS_ROLE PRIMARY KEY (ID),
    -- 保证授权所使用的稳定角色代码全局唯一。
    CONSTRAINT UK_SYS_ROLE_CODE UNIQUE (ROLE_CODE),
    -- 将角色状态限制在启用和停用两种受支持状态内。
    CONSTRAINT CK_SYS_ROLE_STATUS CHECK (STATUS IN ('ACTIVE', 'DISABLED'))
);

COMMENT ON TABLE SYS_ROLE IS '系统角色定义；角色代码作为授权配置和 Java 安全表达式之间的稳定业务契约。';
COMMENT ON COLUMN SYS_ROLE.ID IS '角色唯一标识；供用户角色关联表引用。';
COMMENT ON COLUMN SYS_ROLE.ROLE_CODE IS '稳定角色业务代码；用于授权判断，不应随展示名称变化。';
COMMENT ON COLUMN SYS_ROLE.ROLE_NAME IS '面向管理界面的角色展示名称。';
COMMENT ON COLUMN SYS_ROLE.STATUS IS '角色启停状态；ACTIVE 表示启用，DISABLED 表示停用。';
COMMENT ON COLUMN SYS_ROLE.CREATED_AT IS '角色定义创建时间。';
COMMENT ON COLUMN SYS_ROLE.UPDATED_AT IS '角色名称或状态最近更新时间。';

-- 用户与角色的多对多关联；一个用户可承担多个职责，同一角色也可授予多个用户。
CREATE TABLE SYS_USER_ROLE (
    -- 被授予角色的用户标识。
    USER_ID VARCHAR(36) NOT NULL,
    -- 授予用户的角色标识。
    ROLE_ID VARCHAR(36) NOT NULL,
    -- 联合主键保证同一用户与角色的授权关系只保存一次。
    CONSTRAINT PK_SYS_USER_ROLE PRIMARY KEY (USER_ID, ROLE_ID),
    -- 保证授权关系中的用户必须是已存在的系统账号。
    CONSTRAINT FK_SYS_USER_ROLE_USER FOREIGN KEY (USER_ID) REFERENCES SYS_USER (ID),
    -- 保证授权关系中的角色必须来自系统角色定义。
    CONSTRAINT FK_SYS_USER_ROLE_ROLE FOREIGN KEY (ROLE_ID) REFERENCES SYS_ROLE (ID)
);

COMMENT ON TABLE SYS_USER_ROLE IS '用户与角色的多对多关联；一个用户可承担多个职责，同一角色也可授予多个用户。';
COMMENT ON COLUMN SYS_USER_ROLE.USER_ID IS '被授予角色的用户标识。';
COMMENT ON COLUMN SYS_USER_ROLE.ROLE_ID IS '授予用户的角色标识。';

-- 以下四条记录是系统授权模型依赖的基础参考数据，不属于演示或测试 seed。
-- 系统管理员：承担系统级管理职责。
INSERT INTO SYS_ROLE (ID, ROLE_CODE, ROLE_NAME, STATUS, CREATED_AT, UPDATED_AT)
VALUES ('10000000-0000-0000-0000-000000000001', 'SYSTEM_ADMIN', 'System Administrator', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
-- 采购经理：承担采购业务管理职责。
INSERT INTO SYS_ROLE (ID, ROLE_CODE, ROLE_NAME, STATUS, CREATED_AT, UPDATED_AT)
VALUES ('10000000-0000-0000-0000-000000000002', 'PROCUREMENT_MANAGER', 'Procurement Manager', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
-- 审核人员：承担审核执行与结果查看职责。
INSERT INTO SYS_ROLE (ID, ROLE_CODE, ROLE_NAME, STATUS, CREATED_AT, UPDATED_AT)
VALUES ('10000000-0000-0000-0000-000000000003', 'AUDITOR', 'Auditor', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
-- 法务复核人员：承担合同法律复核职责。
INSERT INTO SYS_ROLE (ID, ROLE_CODE, ROLE_NAME, STATUS, CREATED_AT, UPDATED_AT)
VALUES ('10000000-0000-0000-0000-000000000004', 'LEGAL', 'Legal Reviewer', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 认证安全事件审计；持久记录登录、退出和认证失败等事件，独立于可失效的 Session 状态。
CREATE TABLE AUTH_LOGIN_AUDIT (
    -- 安全事件唯一标识。
    ID VARCHAR(36) NOT NULL,
    -- 已识别系统用户标识；认证发生在账号识别之前或账号不存在时允许为空。
    USER_ID VARCHAR(36) NULL,
    -- 认证请求使用的用户名快照；即使账号不存在或以后改名，仍保留当时的审计输入。
    USERNAME VARCHAR(128) NOT NULL,
    -- 安全事件类型，用于区分登录、退出及其他认证事件。
    EVENT_TYPE VARCHAR(32) NOT NULL,
    -- 事件处理结果，用于筛选认证成功或失败记录。
    RESULT VARCHAR(16) NOT NULL,
    -- 认证失败原因；成功或不适用的事件允许为空，避免记录敏感认证细节。
    FAIL_REASON VARCHAR(32) NULL,
    -- 首次处理本次认证动作的请求标识，用于跨日志审计追踪和问题定位。
    REQUEST_ID VARCHAR(128) NULL,
    -- 分布式调用链标识；用于将安全事件关联到同一条技术调用链。
    TRACE_ID VARCHAR(64) NULL,
    -- 请求来源 IP 快照；用于登录风险分析和安全事件调查。
    SOURCE_IP VARCHAR(64) NULL,
    -- 安全事件实际发生时间，是认证审计时间线的主要排序依据。
    OCCURRED_AT TIMESTAMP NOT NULL,
    -- 安全事件表主键，保证每条审计事实唯一。
    CONSTRAINT PK_AUTH_LOGIN_AUDIT PRIMARY KEY (ID)
);

COMMENT ON TABLE AUTH_LOGIN_AUDIT IS '认证安全事件审计；持久记录登录、退出和认证失败等事件，独立于可失效的 Session 状态。';
COMMENT ON COLUMN AUTH_LOGIN_AUDIT.ID IS '安全事件唯一标识。';
COMMENT ON COLUMN AUTH_LOGIN_AUDIT.USER_ID IS '已识别系统用户标识；认证发生在账号识别之前或账号不存在时允许为空。';
COMMENT ON COLUMN AUTH_LOGIN_AUDIT.USERNAME IS '认证请求使用的用户名快照；即使账号不存在或以后改名，仍保留当时的审计输入。';
COMMENT ON COLUMN AUTH_LOGIN_AUDIT.EVENT_TYPE IS '安全事件类型；用于区分登录、退出及其他认证事件。';
COMMENT ON COLUMN AUTH_LOGIN_AUDIT.RESULT IS '事件处理结果；用于筛选认证成功或失败记录。';
COMMENT ON COLUMN AUTH_LOGIN_AUDIT.FAIL_REASON IS '认证失败原因；成功或不适用的事件允许为空。';
COMMENT ON COLUMN AUTH_LOGIN_AUDIT.REQUEST_ID IS '首次处理本次认证动作的请求标识；用于跨日志审计追踪和问题定位。';
COMMENT ON COLUMN AUTH_LOGIN_AUDIT.TRACE_ID IS '分布式调用链标识；用于将安全事件关联到同一条技术调用链。';
COMMENT ON COLUMN AUTH_LOGIN_AUDIT.SOURCE_IP IS '请求来源 IP 快照；用于登录风险分析和安全事件调查。';
COMMENT ON COLUMN AUTH_LOGIN_AUDIT.OCCURRED_AT IS '安全事件实际发生时间；是认证审计时间线的主要排序依据。';

-- 支持按发生时间检索安全事件时间线和保留期处理。
CREATE INDEX IDX_AUTH_LOGIN_AUDIT_OCCURRED ON AUTH_LOGIN_AUDIT (OCCURRED_AT);
-- 支持按登录名回溯连续成功、失败及锁定相关事件。
CREATE INDEX IDX_AUTH_LOGIN_AUDIT_USERNAME ON AUTH_LOGIN_AUDIT (USERNAME, OCCURRED_AT);
-- 支持从分布式 Trace 快速定位对应认证安全事件。
CREATE INDEX IDX_AUTH_LOGIN_AUDIT_TRACE ON AUTH_LOGIN_AUDIT (TRACE_ID);

-- 长期业务操作审计；记录有业务意义的动作与结果，不以普通应用技术日志代替。
-- 技术日志可以轮转或采样，本表作为 DM8 中可长期查询的业务审计事实保留关键上下文。
CREATE TABLE AUDIT_OPERATION_LOG (
    -- 业务操作审计记录唯一标识。
    ID VARCHAR(36) NOT NULL,
    -- 业务事件类型；表达发生了哪一类可审计事件。
    EVENT_TYPE VARCHAR(64) NOT NULL,
    -- 事件所属业务模块，用于限定审计归属和模块级检索。
    MODULE VARCHAR(64) NOT NULL,
    -- 模块内具体操作代码，用于区分创建、审核、查询等动作。
    ACTION VARCHAR(64) NOT NULL,
    -- 已识别操作用户标识；系统动作或无法识别用户的场景允许为空。
    USER_ID VARCHAR(36) NULL,
    -- 操作用户名快照；保留事件发生时便于人工识别的身份信息。
    USERNAME VARCHAR(128) NULL,
    -- 被操作业务资源类型；与资源标识共同定位业务对象。
    RESOURCE_TYPE VARCHAR(64) NULL,
    -- 被操作业务资源标识；没有明确资源对象的事件允许为空。
    RESOURCE_ID VARCHAR(128) NULL,
    -- 操作执行状态；表示技术或流程层面的成功、失败等结果状态。
    RESULT_STATUS VARCHAR(16) NOT NULL,
    -- 业务结果摘要；记录风险等级、状态结论等便于检索的业务输出。
    BUSINESS_RESULT VARCHAR(128) NULL,
    -- 面向审计人员的操作说明，概括本次动作及其业务目的。
    ACTION_DESC VARCHAR(512) NOT NULL,
    -- 必要的补充上下文；用于解释结果但不得写入密码、Token 等敏感信息。
    DETAIL VARCHAR(2000) NULL,
    -- 首次处理本次业务动作的请求标识，用于关联 HTTP 请求和应用日志。
    REQUEST_ID VARCHAR(128) NULL,
    -- 分布式调用链标识，用于跨服务定位同一业务操作链路。
    TRACE_ID VARCHAR(64) NULL,
    -- 业务请求来源 IP 快照，用于责任追踪和安全调查。
    SOURCE_IP VARCHAR(64) NULL,
    -- 业务动作实际发生时间，是审计时间线的主要业务时间。
    OCCURRED_AT TIMESTAMP NOT NULL,
    -- 审计记录写入 DM8 的时间，用于识别事件发生与持久化之间的差异。
    CREATED_AT TIMESTAMP NOT NULL,
    -- 长期业务操作审计表主键，保证每条审计事实唯一。
    CONSTRAINT PK_AUDIT_OPERATION_LOG PRIMARY KEY (ID)
);

COMMENT ON TABLE AUDIT_OPERATION_LOG IS '长期业务操作审计；记录有业务意义的动作与结果，不以可轮转或采样的普通应用技术日志代替。';
COMMENT ON COLUMN AUDIT_OPERATION_LOG.ID IS '业务操作审计记录唯一标识。';
COMMENT ON COLUMN AUDIT_OPERATION_LOG.EVENT_TYPE IS '业务事件类型；表达发生了哪一类可审计事件。';
COMMENT ON COLUMN AUDIT_OPERATION_LOG.MODULE IS '事件所属业务模块；用于限定审计归属和模块级检索。';
COMMENT ON COLUMN AUDIT_OPERATION_LOG.ACTION IS '模块内具体操作代码；用于区分创建、审核、查询等动作。';
COMMENT ON COLUMN AUDIT_OPERATION_LOG.USER_ID IS '已识别操作用户标识；系统动作或无法识别用户的场景允许为空。';
COMMENT ON COLUMN AUDIT_OPERATION_LOG.USERNAME IS '操作用户名快照；保留事件发生时便于人工识别的身份信息。';
COMMENT ON COLUMN AUDIT_OPERATION_LOG.RESOURCE_TYPE IS '被操作业务资源类型；与资源标识共同定位业务对象。';
COMMENT ON COLUMN AUDIT_OPERATION_LOG.RESOURCE_ID IS '被操作业务资源标识；没有明确资源对象的事件允许为空。';
COMMENT ON COLUMN AUDIT_OPERATION_LOG.RESULT_STATUS IS '操作执行状态；表示技术或流程层面的成功、失败等结果状态。';
COMMENT ON COLUMN AUDIT_OPERATION_LOG.BUSINESS_RESULT IS '业务结果摘要；记录风险等级、状态结论等便于检索的业务输出。';
COMMENT ON COLUMN AUDIT_OPERATION_LOG.ACTION_DESC IS '面向审计人员的操作说明；概括本次动作及其业务目的。';
COMMENT ON COLUMN AUDIT_OPERATION_LOG.DETAIL IS '必要的补充上下文；不得写入密码、Token 等敏感信息。';
COMMENT ON COLUMN AUDIT_OPERATION_LOG.REQUEST_ID IS '首次处理本次业务动作的请求标识；用于关联 HTTP 请求和应用日志。';
COMMENT ON COLUMN AUDIT_OPERATION_LOG.TRACE_ID IS '分布式调用链标识；用于跨服务定位同一业务操作链路。';
COMMENT ON COLUMN AUDIT_OPERATION_LOG.SOURCE_IP IS '业务请求来源 IP 快照；用于责任追踪和安全调查。';
COMMENT ON COLUMN AUDIT_OPERATION_LOG.OCCURRED_AT IS '业务动作实际发生时间；是审计时间线的主要业务时间。';
COMMENT ON COLUMN AUDIT_OPERATION_LOG.CREATED_AT IS '审计记录写入 DM8 的时间；用于识别事件发生与持久化之间的差异。';

-- 支持按发生时间生成全局业务操作时间线。
CREATE INDEX IDX_AUDIT_OP_OCCURRED ON AUDIT_OPERATION_LOG (OCCURRED_AT);
-- 支持按用户回溯其历史业务操作。
CREATE INDEX IDX_AUDIT_OP_USER ON AUDIT_OPERATION_LOG (USER_ID, OCCURRED_AT);
-- 支持按模块和动作统计、排查特定业务用例。
CREATE INDEX IDX_AUDIT_OP_MODULE_ACTION ON AUDIT_OPERATION_LOG (MODULE, ACTION, OCCURRED_AT);
-- 支持定位某一业务资源的完整操作历史。
CREATE INDEX IDX_AUDIT_OP_RESOURCE ON AUDIT_OPERATION_LOG (RESOURCE_TYPE, RESOURCE_ID);
-- 支持从分布式 Trace 关联长期业务审计记录。
CREATE INDEX IDX_AUDIT_OP_TRACE ON AUDIT_OPERATION_LOG (TRACE_ID);
-- 支持从 HTTP RequestId 定位首次处理该动作的审计记录。
CREATE INDEX IDX_AUDIT_OP_REQUEST ON AUDIT_OPERATION_LOG (REQUEST_ID);
