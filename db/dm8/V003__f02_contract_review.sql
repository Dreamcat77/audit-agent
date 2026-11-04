-- V003：在 V001 任务基线和 V002 认证/审计基线之上，建立 F02 合同量化一致性审查持久化模型。
-- DM8 是合同、审核任务、检查结果、规则版本和持久化幂等记录的正式业务事实源。
-- F02 对已持久化的结构化数据执行五条 Java 确定性规则，本身不要求调用 AI/OCR；
-- 独立的 Python OCR/AI 服务可以作为非结构化文档转为候选结构化数据的上游，但须经 Java 校验、归一化后持久化。

-- 扩展 V001 的 AUDIT_TASK，使最小任务记录能够关联业务来源、审核类型、执行人和审计链路。
-- 下列 LEGACY、全零 UUID、SYSTEM 和 migration 默认值只用于兼容已有 V001 数据迁移，不是新业务任务的正常运行值。
-- 业务来源类型；与 SOURCE_ID 共同标识触发审核的领域对象类别。
ALTER TABLE AUDIT_TASK ADD SOURCE_TYPE VARCHAR(32) DEFAULT 'LEGACY' NOT NULL;
-- 业务来源对象唯一标识；F02 中通常对应被审核合同 ID。
ALTER TABLE AUDIT_TASK ADD SOURCE_ID VARCHAR(36) DEFAULT '00000000-0000-0000-0000-000000000000' NOT NULL;
-- 审核开始时读取的来源数据版本；用于识别结果所依据的业务事实版本。
ALTER TABLE AUDIT_TASK ADD SOURCE_VERSION BIGINT DEFAULT 0 NOT NULL;
-- 审核类型代码；用于区分 F02 与后续其他审核用例。
ALTER TABLE AUDIT_TASK ADD REVIEW_TYPE VARCHAR(64) DEFAULT 'LEGACY' NOT NULL;
-- 发起审核的用户标识；用于责任追踪和权限边界审计。
ALTER TABLE AUDIT_TASK ADD CREATED_BY VARCHAR(36) DEFAULT 'SYSTEM' NOT NULL;
-- 首次创建审核任务的请求标识；用于关联 HTTP 请求、长期审计和幂等处理。
ALTER TABLE AUDIT_TASK ADD REQUEST_ID VARCHAR(128) DEFAULT 'migration' NOT NULL;
-- 创建审核任务时的调用链标识；用于跨层定位同一次审核执行。
ALTER TABLE AUDIT_TASK ADD TRACE_ID VARCHAR(64) DEFAULT 'migration' NOT NULL;
-- 审核任务创建时间。
ALTER TABLE AUDIT_TASK ADD CREATED_AT TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL;
-- 审核任务实际开始执行规则的时间；尚未开始时为空。
ALTER TABLE AUDIT_TASK ADD STARTED_AT TIMESTAMP NULL;
-- 审核任务完成或失败的收口时间；仍在执行时为空。
ALTER TABLE AUDIT_TASK ADD FINISHED_AT TIMESTAMP NULL;
-- 乐观并发版本号；用于任务状态转换时检测并发覆盖和重复收口。
ALTER TABLE AUDIT_TASK ADD VERSION_NO BIGINT DEFAULT 0 NOT NULL;

COMMENT ON TABLE AUDIT_TASK IS '审核任务事实表；关联业务来源、审核类型、执行人、生命周期和审计链路。';
COMMENT ON COLUMN AUDIT_TASK.SOURCE_TYPE IS '业务来源类型；与 SOURCE_ID 共同标识触发审核的领域对象类别。';
COMMENT ON COLUMN AUDIT_TASK.SOURCE_ID IS '业务来源对象唯一标识；F02 中通常对应被审核合同 ID。';
COMMENT ON COLUMN AUDIT_TASK.SOURCE_VERSION IS '审核开始时读取的来源数据版本；用于识别结果所依据的业务事实版本。';
COMMENT ON COLUMN AUDIT_TASK.REVIEW_TYPE IS '审核类型代码；用于区分 F02 与其他审核用例。';
COMMENT ON COLUMN AUDIT_TASK.CREATED_BY IS '发起审核的用户标识；用于责任追踪和权限边界审计。';
COMMENT ON COLUMN AUDIT_TASK.REQUEST_ID IS '首次创建审核任务的请求标识；用于关联 HTTP 请求、长期审计和幂等处理。';
COMMENT ON COLUMN AUDIT_TASK.TRACE_ID IS '创建审核任务时的调用链标识；用于跨层定位同一次审核执行。';
COMMENT ON COLUMN AUDIT_TASK.CREATED_AT IS '审核任务创建时间。';
COMMENT ON COLUMN AUDIT_TASK.STARTED_AT IS '审核任务实际开始执行规则的时间；尚未开始时为空。';
COMMENT ON COLUMN AUDIT_TASK.FINISHED_AT IS '审核任务完成或失败的收口时间；仍在执行时为空。';
COMMENT ON COLUMN AUDIT_TASK.VERSION_NO IS '乐观并发版本号；用于任务状态转换时检测并发覆盖和重复收口。';

-- 支持按业务来源、审核类型和创建时间定位某对象的审核任务及最新结果。
CREATE INDEX IDX_AUDIT_TASK_SOURCE ON AUDIT_TASK (SOURCE_TYPE, SOURCE_ID, REVIEW_TYPE, CREATED_AT);

-- 将长期业务操作审计与具体审核任务关联，便于从操作记录追溯任务状态和检查结果。
ALTER TABLE AUDIT_OPERATION_LOG ADD TASK_ID VARCHAR(36) NULL;
COMMENT ON COLUMN AUDIT_OPERATION_LOG.TASK_ID IS '关联的审核任务标识；用于从长期操作审计追溯具体任务状态和检查结果。';
-- 支持按审核任务快速检索其 START、COMPLETE、FAILURE 等长期操作审计记录。
CREATE INDEX IDX_AUDIT_OP_TASK ON AUDIT_OPERATION_LOG (TASK_ID);

-- 结构化合同核心业务事实表；保存 F02 确定性规则直接读取的合同主体、金额和版本信息。
-- 正式数据可来自外部业务系统，也可来自 OCR/AI 上游提取后经 Java 校验、归一化并持久化的数据；
-- 本表不直接表示或保存未经业务校验的 AI 生成结果。
CREATE TABLE CONTRACT (
    -- 合同唯一标识；作为分项、中标依据和审核任务来源的稳定关联键。
    ID VARCHAR(36) NOT NULL,
    -- 合同业务编号；用于外部展示和业务检索，数据库层要求唯一。
    CONTRACT_NO VARCHAR(128) NOT NULL,
    -- 合同正式名称；用于业务识别和审核结果展示。
    CONTRACT_NAME VARCHAR(256) NOT NULL,
    -- 合同业务分类；为规则适用范围和业务查询提供分类依据。
    CONTRACT_TYPE VARCHAR(64) NOT NULL,
    -- 合同甲方名称快照；表示当前结构化合同事实中的采购或委托主体。
    PARTY_A VARCHAR(256) NOT NULL,
    -- 合同乙方名称快照；用于供应商展示及与中标供应商交叉比对。
    PARTY_B VARCHAR(256) NOT NULL,
    -- 合同乙方统一社会信用代码；存在时优先作为供应商身份比对依据。
    PARTY_B_CREDIT_CODE VARCHAR(64) NULL,
    -- 合同数字总金额；F02 金额规则的合同侧基准值，使用定点小数保存人民币金额。
    CONTRACT_AMOUNT DECIMAL(18,2) NOT NULL,
    -- 合同中文大写金额；为空时相关规则可形成不可评估结果，而非伪造比较值。
    AMOUNT_IN_WORDS VARCHAR(256) NULL,
    -- 合同当前业务状态；供业务流程和审核适用性判断使用。
    STATUS VARCHAR(32) NOT NULL,
    -- 乐观并发版本号；标识审核所依据的合同结构化事实版本。
    VERSION_NO BIGINT DEFAULT 0 NOT NULL,
    -- 合同结构化记录创建时间。
    CREATED_AT TIMESTAMP NOT NULL,
    -- 合同结构化事实最近更新时间。
    UPDATED_AT TIMESTAMP NOT NULL,
    -- 合同表主键，保证每份结构化合同可被稳定引用。
    CONSTRAINT PK_CONTRACT PRIMARY KEY (ID),
    -- 保证合同业务编号唯一，避免外部检索编号映射到多份合同。
    CONSTRAINT UK_CONTRACT_NO UNIQUE (CONTRACT_NO),
    -- 合同金额不得为负，防止无效金额进入确定性规则计算。
    CONSTRAINT CK_CONTRACT_AMOUNT CHECK (CONTRACT_AMOUNT >= 0),
    -- 合同乐观并发版本号不得为负。
    CONSTRAINT CK_CONTRACT_VERSION CHECK (VERSION_NO >= 0)
);

COMMENT ON TABLE CONTRACT IS '结构化合同核心业务事实表；保存 F02 确定性规则直接读取的合同主体、金额和版本信息。';
COMMENT ON COLUMN CONTRACT.ID IS '合同唯一标识；作为分项、中标依据和审核任务来源的稳定关联键。';
COMMENT ON COLUMN CONTRACT.CONTRACT_NO IS '合同业务编号；用于外部展示和业务检索，数据库层要求唯一。';
COMMENT ON COLUMN CONTRACT.CONTRACT_NAME IS '合同正式名称；用于业务识别和审核结果展示。';
COMMENT ON COLUMN CONTRACT.CONTRACT_TYPE IS '合同业务分类；为规则适用范围和业务查询提供分类依据。';
COMMENT ON COLUMN CONTRACT.PARTY_A IS '合同甲方名称快照；表示当前结构化合同事实中的采购或委托主体。';
COMMENT ON COLUMN CONTRACT.PARTY_B IS '合同乙方名称快照；用于供应商展示及与中标供应商交叉比对。';
COMMENT ON COLUMN CONTRACT.PARTY_B_CREDIT_CODE IS '合同乙方统一社会信用代码；存在时优先作为供应商身份比对依据。';
COMMENT ON COLUMN CONTRACT.CONTRACT_AMOUNT IS '合同数字总金额；F02 金额规则的合同侧基准值，使用定点小数保存人民币金额。';
COMMENT ON COLUMN CONTRACT.AMOUNT_IN_WORDS IS '合同中文大写金额；为空时相关规则可形成不可评估结果。';
COMMENT ON COLUMN CONTRACT.STATUS IS '合同当前业务状态；供业务流程和审核适用性判断使用。';
COMMENT ON COLUMN CONTRACT.VERSION_NO IS '乐观并发版本号；标识审核所依据的合同结构化事实版本。';
COMMENT ON COLUMN CONTRACT.CREATED_AT IS '合同结构化记录创建时间。';
COMMENT ON COLUMN CONTRACT.UPDATED_AT IS '合同结构化事实最近更新时间。';

-- 合同分项金额结构；为 F02 分项算术校验及分项声明金额合计校验提供逐项事实。
CREATE TABLE CONTRACT_LINE_ITEM (
    -- 合同分项唯一标识。
    ID VARCHAR(36) NOT NULL,
    -- 所属合同标识；将分项限定在一份结构化合同内。
    CONTRACT_ID VARCHAR(36) NOT NULL,
    -- 合同范围内稳定的业务项标识；审核结果通过该值定位具体失败分项。
    ITEM_KEY VARCHAR(128) NOT NULL,
    -- 分项业务名称；用于审核结果和人工复核展示。
    ITEM_NAME VARCHAR(256) NOT NULL,
    -- 分项数量；与单价相乘形成 F02 分项算术规则的计算金额。
    QUANTITY DECIMAL(18,4) NOT NULL,
    -- 分项单价；保留四位小数以支持数量与单价的确定性计算。
    UNIT_PRICE DECIMAL(18,4) NOT NULL,
    -- 合同声明的分项金额；分别与数量乘单价结果及合同总金额进行比对。
    DECLARED_AMOUNT DECIMAL(18,2) NOT NULL,
    -- 分项在合同中的稳定展示顺序；不作为分项业务身份标识。
    SORT_NO INTEGER NOT NULL,
    -- 合同分项表主键，保证每条分项记录唯一。
    CONSTRAINT PK_CONTRACT_LINE_ITEM PRIMARY KEY (ID),
    -- 保证同一合同内 ITEM_KEY 唯一，使规则结果能够无歧义地引用业务分项。
    CONSTRAINT UK_CONTRACT_LINE_KEY UNIQUE (CONTRACT_ID, ITEM_KEY),
    -- 保证每条分项必须归属于已存在的合同。
    CONSTRAINT FK_CONTRACT_LINE_CONTRACT FOREIGN KEY (CONTRACT_ID) REFERENCES CONTRACT (ID),
    -- 数量、单价和声明金额均不得为负，避免无效金额结构进入规则计算。
    CONSTRAINT CK_CONTRACT_LINE_VALUES CHECK (
        QUANTITY >= 0 AND UNIT_PRICE >= 0 AND DECLARED_AMOUNT >= 0)
);

COMMENT ON TABLE CONTRACT_LINE_ITEM IS '合同分项金额结构；为 F02 分项算术校验及分项声明金额合计校验提供逐项事实。';
COMMENT ON COLUMN CONTRACT_LINE_ITEM.ID IS '合同分项唯一标识。';
COMMENT ON COLUMN CONTRACT_LINE_ITEM.CONTRACT_ID IS '所属合同标识；将分项限定在一份结构化合同内。';
COMMENT ON COLUMN CONTRACT_LINE_ITEM.ITEM_KEY IS '合同范围内稳定的业务项标识；审核结果通过该值定位具体失败分项。';
COMMENT ON COLUMN CONTRACT_LINE_ITEM.ITEM_NAME IS '分项业务名称；用于审核结果和人工复核展示。';
COMMENT ON COLUMN CONTRACT_LINE_ITEM.QUANTITY IS '分项数量；与单价相乘形成 F02 分项算术规则的计算金额。';
COMMENT ON COLUMN CONTRACT_LINE_ITEM.UNIT_PRICE IS '分项单价；保留四位小数以支持数量与单价的确定性计算。';
COMMENT ON COLUMN CONTRACT_LINE_ITEM.DECLARED_AMOUNT IS '合同声明的分项金额；与数量乘单价结果及合同总金额进行比对。';
COMMENT ON COLUMN CONTRACT_LINE_ITEM.SORT_NO IS '分项在合同中的稳定展示顺序；不作为分项业务身份标识。';

-- 支持按合同及业务顺序读取完整分项列表，供确定性规则一次性计算。
CREATE INDEX IDX_CONTRACT_LINE_CONTRACT ON CONTRACT_LINE_ITEM (CONTRACT_ID, SORT_NO);

-- 与合同关联的结构化中标通知依据；用于 F02 对合同金额和乙方供应商进行交叉比对。
CREATE TABLE CONTRACT_AWARD_NOTICE (
    -- 中标依据记录唯一标识。
    ID VARCHAR(36) NOT NULL,
    -- 所属合同标识；唯一约束保证一份合同最多关联一条当前中标依据。
    CONTRACT_ID VARCHAR(36) NOT NULL,
    -- 中标通知业务编号；用于人工核对和来源追溯。
    NOTICE_NO VARCHAR(128) NOT NULL,
    -- 中标项目名称；保留中标依据中的项目语义。
    PROJECT_NAME VARCHAR(256) NOT NULL,
    -- 中标供应商名称；在信用代码缺失时作为规范化名称比对依据。
    WINNING_BIDDER VARCHAR(256) NOT NULL,
    -- 中标供应商统一社会信用代码；存在时优先用于供应商身份一致性判断。
    WINNING_BIDDER_CREDIT_CODE VARCHAR(64) NULL,
    -- 中标金额；作为合同总金额交叉比对的中标侧基准值。
    WINNING_AMOUNT DECIMAL(18,2) NOT NULL,
    -- 中标日期；记录中标依据形成的业务时间。
    WINNING_DATE DATE NOT NULL,
    -- 中标依据来源引用字段；可保存上游业务引用，不代表本迁移已经实现文件存储能力。
    SOURCE_REF VARCHAR(512) NULL,
    -- 中标依据结构化记录创建时间。
    CREATED_AT TIMESTAMP NOT NULL,
    -- 中标依据表主键，保证每条结构化依据唯一。
    CONSTRAINT PK_CONTRACT_AWARD_NOTICE PRIMARY KEY (ID),
    -- 保证一份合同最多保存一条当前用于审核的中标依据。
    CONSTRAINT UK_CONTRACT_AWARD_CONTRACT UNIQUE (CONTRACT_ID),
    -- 保证中标依据只能关联已存在的合同。
    CONSTRAINT FK_CONTRACT_AWARD_CONTRACT FOREIGN KEY (CONTRACT_ID) REFERENCES CONTRACT (ID),
    -- 中标金额不得为负，避免无效金额进入合同金额比对。
    CONSTRAINT CK_CONTRACT_AWARD_AMOUNT CHECK (WINNING_AMOUNT >= 0)
);

COMMENT ON TABLE CONTRACT_AWARD_NOTICE IS '与合同关联的结构化中标通知依据；用于 F02 对合同金额和乙方供应商进行交叉比对。';
COMMENT ON COLUMN CONTRACT_AWARD_NOTICE.ID IS '中标依据记录唯一标识。';
COMMENT ON COLUMN CONTRACT_AWARD_NOTICE.CONTRACT_ID IS '所属合同标识；一份合同最多关联一条当前中标依据。';
COMMENT ON COLUMN CONTRACT_AWARD_NOTICE.NOTICE_NO IS '中标通知业务编号；用于人工核对和来源追溯。';
COMMENT ON COLUMN CONTRACT_AWARD_NOTICE.PROJECT_NAME IS '中标项目名称；保留中标依据中的项目语义。';
COMMENT ON COLUMN CONTRACT_AWARD_NOTICE.WINNING_BIDDER IS '中标供应商名称；在信用代码缺失时作为规范化名称比对依据。';
COMMENT ON COLUMN CONTRACT_AWARD_NOTICE.WINNING_BIDDER_CREDIT_CODE IS '中标供应商统一社会信用代码；存在时优先用于供应商身份一致性判断。';
COMMENT ON COLUMN CONTRACT_AWARD_NOTICE.WINNING_AMOUNT IS '中标金额；作为合同总金额交叉比对的中标侧基准值。';
COMMENT ON COLUMN CONTRACT_AWARD_NOTICE.WINNING_DATE IS '中标日期；记录中标依据形成的业务时间。';
COMMENT ON COLUMN CONTRACT_AWARD_NOTICE.SOURCE_REF IS '中标依据来源引用字段；不代表已实现文件存储能力。';
COMMENT ON COLUMN CONTRACT_AWARD_NOTICE.CREATED_AT IS '中标依据结构化记录创建时间。';

-- F02 确定性规则运行元数据；数据库配置与 Java Rule 通过稳定 RULE_CODE 建立契约。
CREATE TABLE CONTRACT_RULE_CONFIG (
    -- 规则稳定代码；是 Java 实现、配置和持久化审核结果之间不可随意变更的契约键。
    RULE_CODE VARCHAR(64) NOT NULL,
    -- 规则英文名称；用于技术管理和配置识别。
    RULE_NAME VARCHAR(128) NOT NULL,
    -- 规则启用标记：1 表示参与执行，0 表示当前配置停用。
    ENABLED INTEGER DEFAULT 1 NOT NULL,
    -- 规则失败时输出的风险等级，用于汇总整体审核风险。
    FAIL_LEVEL VARCHAR(16) NOT NULL,
    -- 规则失败是否阻断签署：1 表示阻断，0 表示不阻断。
    BLOCK_SIGN INTEGER DEFAULT 1 NOT NULL,
    -- 规则配置版本；执行结果保存该版本以支持历史审计追溯。
    VERSION_NO BIGINT DEFAULT 1 NOT NULL,
    -- 规则用途与判定依据说明，供配置管理和审计人员理解规则意图。
    DESCRIPTION VARCHAR(512) NULL,
    -- 规则配置最近更新时间。
    UPDATED_AT TIMESTAMP NOT NULL,
    -- 以稳定规则代码作为主键，保证每条规则只有一份当前配置。
    CONSTRAINT PK_CONTRACT_RULE_CONFIG PRIMARY KEY (RULE_CODE),
    -- 将启用标记限制为 DM8 中使用的 0/1 布尔表达。
    CONSTRAINT CK_CONTRACT_RULE_ENABLED CHECK (ENABLED IN (0, 1)),
    -- 将阻断签署标记限制为 0/1。
    CONSTRAINT CK_CONTRACT_RULE_BLOCK CHECK (BLOCK_SIGN IN (0, 1)),
    -- 将失败风险等级限制在审核领域支持的 GREEN、YELLOW、RED 范围内。
    CONSTRAINT CK_CONTRACT_RULE_LEVEL CHECK (FAIL_LEVEL IN ('GREEN', 'YELLOW', 'RED'))
);

COMMENT ON TABLE CONTRACT_RULE_CONFIG IS 'F02 确定性规则运行元数据；数据库配置与 Java Rule 通过稳定规则代码建立契约。';
COMMENT ON COLUMN CONTRACT_RULE_CONFIG.RULE_CODE IS '规则稳定代码；是 Java 实现、配置和持久化审核结果之间的契约键。';
COMMENT ON COLUMN CONTRACT_RULE_CONFIG.RULE_NAME IS '规则英文名称；用于技术管理和配置识别。';
COMMENT ON COLUMN CONTRACT_RULE_CONFIG.ENABLED IS '规则启用标记：1 表示参与执行，0 表示当前配置停用。';
COMMENT ON COLUMN CONTRACT_RULE_CONFIG.FAIL_LEVEL IS '规则失败时输出的风险等级；用于汇总整体审核风险。';
COMMENT ON COLUMN CONTRACT_RULE_CONFIG.BLOCK_SIGN IS '规则失败是否阻断签署：1 表示阻断，0 表示不阻断。';
COMMENT ON COLUMN CONTRACT_RULE_CONFIG.VERSION_NO IS '规则配置版本；执行结果保存该版本以支持历史审计追溯。';
COMMENT ON COLUMN CONTRACT_RULE_CONFIG.DESCRIPTION IS '规则用途与判定依据说明；供配置管理和审计人员理解规则意图。';
COMMENT ON COLUMN CONTRACT_RULE_CONFIG.UPDATED_AT IS '规则配置最近更新时间。';

-- 以下五条 seed 是 F02 Java 确定性规则的正式基础元数据，不属于 DEV Mock 数据。
-- F02_AMOUNT_WORDS_MATCH：校验数字合同金额与中文大写金额是否表达同一数值。
INSERT INTO CONTRACT_RULE_CONFIG
    (RULE_CODE, RULE_NAME, ENABLED, FAIL_LEVEL, BLOCK_SIGN, VERSION_NO, DESCRIPTION, UPDATED_AT)
VALUES
    ('F02_AMOUNT_WORDS_MATCH', 'Amount in words matches numeric amount', 1, 'RED', 1, 1,
     'Deterministic Java comparison', CURRENT_TIMESTAMP);
-- F02_LINE_ITEM_ARITHMETIC：逐项校验 quantity × unitPrice 与 declaredAmount 是否一致。
INSERT INTO CONTRACT_RULE_CONFIG
    (RULE_CODE, RULE_NAME, ENABLED, FAIL_LEVEL, BLOCK_SIGN, VERSION_NO, DESCRIPTION, UPDATED_AT)
VALUES
    ('F02_LINE_ITEM_ARITHMETIC', 'Line item arithmetic', 1, 'RED', 1, 1,
     'Quantity multiplied by unit price', CURRENT_TIMESTAMP);
-- F02_LINE_SUM_MATCH：校验全部分项声明金额合计与合同总金额是否一致。
INSERT INTO CONTRACT_RULE_CONFIG
    (RULE_CODE, RULE_NAME, ENABLED, FAIL_LEVEL, BLOCK_SIGN, VERSION_NO, DESCRIPTION, UPDATED_AT)
VALUES
    ('F02_LINE_SUM_MATCH', 'Line item sum matches contract amount', 1, 'RED', 1, 1,
     'Sum of declared line amounts', CURRENT_TIMESTAMP);
-- F02_AWARD_AMOUNT_MATCH：校验合同总金额与结构化中标金额是否一致。
INSERT INTO CONTRACT_RULE_CONFIG
    (RULE_CODE, RULE_NAME, ENABLED, FAIL_LEVEL, BLOCK_SIGN, VERSION_NO, DESCRIPTION, UPDATED_AT)
VALUES
    ('F02_AWARD_AMOUNT_MATCH', 'Award amount matches contract amount', 1, 'RED', 1, 1,
     'Comparison against structured award notice basis', CURRENT_TIMESTAMP);
-- F02_AWARD_SUPPLIER_MATCH：校验合同乙方与中标供应商是否一致；统一社会信用代码优先，规范化名称作为回退依据。
INSERT INTO CONTRACT_RULE_CONFIG
    (RULE_CODE, RULE_NAME, ENABLED, FAIL_LEVEL, BLOCK_SIGN, VERSION_NO, DESCRIPTION, UPDATED_AT)
VALUES
    ('F02_AWARD_SUPPLIER_MATCH', 'Award supplier matches contract supplier', 1, 'RED', 1, 1,
     'Credit code preferred, normalized name fallback', CURRENT_TIMESTAMP);

-- 确定性检查结果事实表；保存每次审核任务下每条规则及可选分项的实际执行结论。
CREATE TABLE AUDIT_CHECK_RESULT (
    -- 检查结果唯一标识。
    ID VARCHAR(36) NOT NULL,
    -- 所属审核任务标识；将单条规则结论归入一次完整审核执行。
    TASK_ID VARCHAR(36) NOT NULL,
    -- 实际执行的稳定规则代码；用于解释结果并关联规则配置语义。
    RULE_CODE VARCHAR(64) NOT NULL,
    -- 规则涉及的合同分项业务键；非分项规则或无法定位具体分项时允许为空。
    ITEM_KEY VARCHAR(128) NULL,
    -- 检查状态：PASS、FAIL 或因依据不足等原因形成的 NOT_EVALUATED。
    CHECK_STATUS VARCHAR(32) NOT NULL,
    -- 本条结果风险等级；用于审核任务整体风险汇总。
    RISK_LEVEL VARCHAR(16) NOT NULL,
    -- 本条规则失败是否阻断签署的执行快照，以 0/1 表示。
    BLOCK_SIGN INTEGER NOT NULL,
    -- 规则执行时取得或计算的实际值快照；不适用或无法评估时允许为空。
    ACTUAL_VALUE VARCHAR(512) NULL,
    -- 规则期望值或对比基准快照；不适用或无法评估时允许为空。
    EXPECTED_VALUE VARCHAR(512) NULL,
    -- 面向审核人员的确定性判定说明，解释通过、失败或不可评估原因。
    DETAIL VARCHAR(1000) NOT NULL,
    -- 本次审核实际使用的规则配置版本；即使后续配置变化，历史结果仍可准确追溯。
    RULE_VERSION BIGINT NOT NULL,
    -- 检查结果持久化时间。
    CREATED_AT TIMESTAMP NOT NULL,
    -- 检查结果表主键，保证每条执行结论唯一。
    CONSTRAINT PK_AUDIT_CHECK_RESULT PRIMARY KEY (ID),
    -- 保证检查结果必须归属于已存在的审核任务。
    CONSTRAINT FK_AUDIT_CHECK_TASK FOREIGN KEY (TASK_ID) REFERENCES AUDIT_TASK (ID),
    -- 将检查状态限制在确定性规则执行支持的三种结论内。
    CONSTRAINT CK_AUDIT_CHECK_STATUS CHECK (CHECK_STATUS IN ('PASS', 'FAIL', 'NOT_EVALUATED')),
    -- 将风险等级限制在审核领域支持的三个等级内。
    CONSTRAINT CK_AUDIT_CHECK_RISK CHECK (RISK_LEVEL IN ('GREEN', 'YELLOW', 'RED')),
    -- 将阻断签署标记限制为 0/1。
    CONSTRAINT CK_AUDIT_CHECK_BLOCK CHECK (BLOCK_SIGN IN (0, 1))
);

COMMENT ON TABLE AUDIT_CHECK_RESULT IS '确定性检查结果事实表；保存每次审核任务下每条规则及可选分项的实际执行结论。';
COMMENT ON COLUMN AUDIT_CHECK_RESULT.ID IS '检查结果唯一标识。';
COMMENT ON COLUMN AUDIT_CHECK_RESULT.TASK_ID IS '所属审核任务标识；将单条规则结论归入一次完整审核执行。';
COMMENT ON COLUMN AUDIT_CHECK_RESULT.RULE_CODE IS '实际执行的稳定规则代码；用于解释结果并关联规则配置语义。';
COMMENT ON COLUMN AUDIT_CHECK_RESULT.ITEM_KEY IS '规则涉及的合同分项业务键；非分项规则或无法定位具体分项时允许为空。';
COMMENT ON COLUMN AUDIT_CHECK_RESULT.CHECK_STATUS IS '检查状态：PASS、FAIL 或因依据不足等原因形成的 NOT_EVALUATED。';
COMMENT ON COLUMN AUDIT_CHECK_RESULT.RISK_LEVEL IS '本条结果风险等级；用于审核任务整体风险汇总。';
COMMENT ON COLUMN AUDIT_CHECK_RESULT.BLOCK_SIGN IS '本条规则失败是否阻断签署的执行快照，以 0/1 表示。';
COMMENT ON COLUMN AUDIT_CHECK_RESULT.ACTUAL_VALUE IS '规则执行时取得或计算的实际值快照；不适用或无法评估时允许为空。';
COMMENT ON COLUMN AUDIT_CHECK_RESULT.EXPECTED_VALUE IS '规则期望值或对比基准快照；不适用或无法评估时允许为空。';
COMMENT ON COLUMN AUDIT_CHECK_RESULT.DETAIL IS '面向审核人员的确定性判定说明；解释通过、失败或不可评估原因。';
COMMENT ON COLUMN AUDIT_CHECK_RESULT.RULE_VERSION IS '本次审核实际使用的规则配置版本；用于准确追溯历史结果。';
COMMENT ON COLUMN AUDIT_CHECK_RESULT.CREATED_AT IS '检查结果持久化时间。';

-- 支持按审核任务和规则代码读取完整结果集、生成偏差列表及签署检查清单。
CREATE INDEX IDX_AUDIT_CHECK_TASK ON AUDIT_CHECK_RESULT (TASK_ID, RULE_CODE);

-- 有副作用业务操作的持久化幂等状态；以 DM8 为事实源，防止重复请求重复创建审核任务或检查结果。
CREATE TABLE IDEMPOTENCY_RECORD (
    -- 幂等记录唯一标识。
    ID VARCHAR(36) NOT NULL,
    -- 幂等业务作用域；区分不同写用例对同名幂等键的解释。
    SCOPE VARCHAR(64) NOT NULL,
    -- 客户端为一次业务动作提供的幂等键；在同一 SCOPE 下必须唯一。
    IDEMPOTENCY_KEY VARCHAR(128) NOT NULL,
    -- 首次提交该幂等动作的用户标识；用于绑定请求主体并防止跨用户复用。
    ACTOR_ID VARCHAR(36) NOT NULL,
    -- 本次写操作针对的业务资源类型。
    RESOURCE_TYPE VARCHAR(64) NOT NULL,
    -- 本次写操作针对的业务资源标识。
    RESOURCE_ID VARCHAR(128) NOT NULL,
    -- 对影响业务语义的请求内容生成的指纹；用于识别同一幂等键承载不同请求的问题。
    REQUEST_FINGERPRINT VARCHAR(64) NOT NULL,
    -- 幂等处理状态：IN_PROGRESS 已认领处理中，SUCCEEDED 已成功，FAILED 已失败收口。
    STATUS VARCHAR(16) NOT NULL,
    -- 成功结果资源类型；处理中或没有可重放结果时允许为空。
    RESULT_RESOURCE_TYPE VARCHAR(64) NULL,
    -- 成功结果资源标识；F02 可据此定位已创建的审核任务并重放结果。
    RESULT_RESOURCE_ID VARCHAR(128) NULL,
    -- 首次处理该幂等动作的请求标识；重试不得覆盖，便于追溯原始请求。
    FIRST_REQUEST_ID VARCHAR(128) NOT NULL,
    -- 首次处理动作时的调用链标识；用于关联幂等认领、任务和审计日志。
    TRACE_ID VARCHAR(64) NOT NULL,
    -- 幂等记录成功认领并持久化的时间。
    CREATED_AT TIMESTAMP NOT NULL,
    -- 幂等动作成功或失败收口时间；仍处于 IN_PROGRESS 时为空。
    COMPLETED_AT TIMESTAMP NULL,
    -- 乐观并发版本号；用于幂等状态转换时识别并发完成或失败写入。
    VERSION_NO BIGINT DEFAULT 0 NOT NULL,
    -- 幂等记录表主键，保证每条认领记录唯一。
    CONSTRAINT PK_IDEMPOTENCY_RECORD PRIMARY KEY (ID),
    -- 保证同一业务 SCOPE 下幂等键唯一，是重复请求只认领一次的数据库约束基础。
    CONSTRAINT UK_IDEMPOTENCY_SCOPE_KEY UNIQUE (SCOPE, IDEMPOTENCY_KEY),
    -- 将幂等状态限制在认领、成功和失败三个持久化阶段内。
    CONSTRAINT CK_IDEMPOTENCY_STATUS CHECK (STATUS IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED'))
);

COMMENT ON TABLE IDEMPOTENCY_RECORD IS '有副作用业务操作的持久化幂等状态；以 DM8 为事实源，防止重复请求重复创建任务或结果。';
COMMENT ON COLUMN IDEMPOTENCY_RECORD.ID IS '幂等记录唯一标识。';
COMMENT ON COLUMN IDEMPOTENCY_RECORD.SCOPE IS '幂等业务作用域；区分不同写用例对同名幂等键的解释。';
COMMENT ON COLUMN IDEMPOTENCY_RECORD.IDEMPOTENCY_KEY IS '客户端为一次业务动作提供的幂等键；在同一作用域下必须唯一。';
COMMENT ON COLUMN IDEMPOTENCY_RECORD.ACTOR_ID IS '首次提交该幂等动作的用户标识；用于绑定请求主体并防止跨用户复用。';
COMMENT ON COLUMN IDEMPOTENCY_RECORD.RESOURCE_TYPE IS '本次写操作针对的业务资源类型。';
COMMENT ON COLUMN IDEMPOTENCY_RECORD.RESOURCE_ID IS '本次写操作针对的业务资源标识。';
COMMENT ON COLUMN IDEMPOTENCY_RECORD.REQUEST_FINGERPRINT IS '影响业务语义的请求内容指纹；用于识别同一幂等键承载不同请求的问题。';
COMMENT ON COLUMN IDEMPOTENCY_RECORD.STATUS IS '幂等处理状态：IN_PROGRESS 处理中，SUCCEEDED 已成功，FAILED 已失败收口。';
COMMENT ON COLUMN IDEMPOTENCY_RECORD.RESULT_RESOURCE_TYPE IS '成功结果资源类型；处理中或没有可重放结果时允许为空。';
COMMENT ON COLUMN IDEMPOTENCY_RECORD.RESULT_RESOURCE_ID IS '成功结果资源标识；F02 可据此定位已创建的审核任务并重放结果。';
COMMENT ON COLUMN IDEMPOTENCY_RECORD.FIRST_REQUEST_ID IS '首次处理该幂等动作的请求标识；重试不得覆盖。';
COMMENT ON COLUMN IDEMPOTENCY_RECORD.TRACE_ID IS '首次处理动作时的调用链标识；用于关联幂等认领、任务和审计日志。';
COMMENT ON COLUMN IDEMPOTENCY_RECORD.CREATED_AT IS '幂等记录成功认领并持久化的时间。';
COMMENT ON COLUMN IDEMPOTENCY_RECORD.COMPLETED_AT IS '幂等动作成功或失败收口时间；仍处于 IN_PROGRESS 时为空。';
COMMENT ON COLUMN IDEMPOTENCY_RECORD.VERSION_NO IS '乐观并发版本号；用于幂等状态转换时识别并发完成或失败写入。';

-- 支持按业务资源查询相关幂等动作和已生成结果。
CREATE INDEX IDX_IDEMPOTENCY_RESOURCE ON IDEMPOTENCY_RECORD (RESOURCE_TYPE, RESOURCE_ID);
-- 支持按创建时间排查长期未完成记录及执行保留期管理。
CREATE INDEX IDX_IDEMPOTENCY_CREATED ON IDEMPOTENCY_RECORD (CREATED_AT);
