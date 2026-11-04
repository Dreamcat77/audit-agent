# 合同自动审查系统完整改造与部署交付报告

日期：2026-10-27。执行顺序：合同自动化改造 → 26三模块实际测试和修复 → Git/部署与甲方ARM离线包。没有自动关机。

## 实现范围与数据边界

合同新增独立ContractAnalysisCase，由用户上传材料后自动读取、提取事实与条款、跨文件核对并生成有来源的完整或部分报告；无需材料类型、项目或合同信息人工填表确认。原Project、Contract、ReviewSession和AuditTask模型继续独立，保留原正式合同和五步审查，不以新案件ID冒充AuditTask ID。范围不足和技术执行失败分别说明，不把无结果、超时或UNKNOWN伪装成无风险。

预算按批次汇总申报和材料批复金额，差异按项目/科目归集，保留超支、错支、漏项来源和差异清单；采购保留既有一期、二期、三期成果，自动入口执行询比价、预审及F10。预算与最高限价、供应商报价分别处理，中文大写金额与BigDecimal精确计算，不混用金额角色。采购新入口不宣称黑名单、围串标和拆分采购已执行。

DM8保存任务、冻结结果、证据、owner权限、幂等和审计；Redis只作会话/可丢失缓存。短事务、租约及版本CAS阻止迟到覆盖；不明确的模型POST不盲目重发。刷新、重启、新登录均有历史发现/重开路径。Java与Python通过HTTP适配，不在Java嵌入OCR原生依赖。

新增数据库对象仅为CONTRACT_ANALYSIS_CASE和AUDIT_CASE、AUDIT_CASE_FILE、AUDIT_FACT、AUDIT_QUOTE_LINE；另保留本轮明确的系统自动分类来源约束增量。未修改V016原始SQL、未修补甲方业务数据。更新前检查必要列/金额精度/默认值/启用约束/键关系及索引顺序，已知等价DM8表达按限定语法处理；真漂移和不明部分迁移停止，不能自动修表或重放未知SQL。

代码清单以Git提交为准：backend/src为Java编排、端口和DM8适配；services/audit-ai/src为模型/OCR及结构校验；frontend/src为上传、批次结果和历史；db/dm8为增量DDL；scripts/offline/customer-v10和tools/build-customer-v10.py为升级与制包工具。累计Chrome109字体/颜色修复提交保留在分支历史。

## 实际测试结果

- JDK17全量clean verify：3333 tests，0 failures，0 errors，24 skipped。最初13项采购既有回归失败已修复；不再以早期失败快照代表最终结果。
- Python3.11：2029 passed、14 skipped、43 subtests passed。前端821项TypeScript测试及2项UUID MJS测试通过，生产构建通过，保留既有体积/动态导入警告。
- 26真实模型返回名称audit-qwen-27b-16k:latest。真实HTTP→Java→Python→模型→DM8链路验收，不以HTTP200替代业务成功。
- 合同十五组真实场景：11 COMPLETED、4 PARTIAL，471处证据核对通过；覆盖采购/服务/租赁、缺字段、大小写金额、中标与主体、付款冲突/有效补充变更、独立合同混合、坏附件、提示注入、倒置扫描及12行同名明细。第一阶段另有488处核对和明确标注realModel=false的故障注入。
- 最新服务合同复验为PARTIAL：10事实、8条款、22处引用通过，未通过原文核对的模型描述被剔除；报告明确覆盖限制，不承诺整份无风险。
- 采购r3正常/矛盾两组各12文件，分别200/215处引用通过。预算222600、中标212000、三报价及9明细核对正确；明确无优先顺序的两套付款条件均引用原文说明冲突，不把报价接近直接判为串标。
- 预算跨文件、超支2240元、中文大写、Excel批复参考、真实漏项及坏附件部分结果通过。9999.99/10000/50000/100000/110000金额段边界、6路幂等、变更选择409、owner隔离和13条历史分页通过。
- 预算/采购各24个多格式样本：23有效及1个明确资源超限负例，断言全部符合预期。真实覆盖14扩展名、UTF16、混合/扫描PDF、90/180/270旋转、Word内倒置图片及26MB Word。jpeg/tif别名未分别跑，不冒称16扩展名均现场验证；填充大文件不代表同体积复杂正文准确率。
- Chrome109.0.5412.0生产HTTP界面：预算上传后自动结果跳转、历史/差异XLSX/390px、采购证据/拒绝后再传/坏附件终态、合同零填表/原文/刷新均通过，pageErrors=[]。实际发现并修复HTTP下randomUUID缺失导致采购POST不发送的问题。
- 23份旧冻结报告重启及新登录只读重开指纹一致。真实DM8131条历史探针覆盖重复时间/精度/owner隔离，fixture回滚；真实JDK丢响应反例只有一次POST。最终8张任务表活动数0，6个服务active。
- 实际journal的8236事件脱敏导出不含正文canary、摘录、prompt、文件名或密钥。测试合成正文可核对；甲方现场导出仅白名单阶段/计数/耗时/reasonCode，不采集材料具体内容。

详细可执行命令及观察结果：docs/qa-26/FINAL_REPORT.md、evidence/browser109-accept-verification.json、evidence/remote/*-verification.json、docs/contract-automation/TEST_REPORT.md。构建日志留在本地受控evidence，不将可能包含环境属性的原始日志提交Git。

## 26部署与Git

26最新验收版本qa26-20261027-r5，独立入口http://192.168.0.26:18082/，使用独立AUTOCASE2/QA26_FULL2、storage和Redis Session namespace；Java19151、AI19153仅loopback。原80端口仍为旧release，不能声称默认入口升级。原业务数据库完整恢复未证明，因此未迁移原库、未替换原服务。原配置/文件哈希和健康均保持。

候选归档SHA256：82524f60afe245370017c14b8f4c5a6f0148f5f019fe4fd1d9694628c9dffb7a。Java JAR SHA256：c5ebe922d4bc241e7c2505668042fe4f4ced8992a8d3d97de727032b0f1ede69。r5仅前端变更，后端/Python与r3逐文件一致。

GitHub仓库ztysew2/audit-agent；新分支feat/contract-auto-delivery-20261027。运行时代码提交4253bb5f27d513d04483bab448050d230dbb908d，制包/部署工具提交11c217d9f1fed5a30943af8913e99ea2fdf16aa8，两次均实际推送并通过ls-remote验证。包内APP_COMMIT锁定后者。源码、必要脚本及脱敏验证元数据按显式清单提交；排除构建目录、归档、原始模型响应、数据库备份和凭据。没有reset/clean/force push。最终交付文档和QA交接工具另行提交，不改变已封包应用源码。

交付时将独立QA primary测试身份交接为用户此前提供的test_admin测试凭据；保留同一owner ID和角色，未修改原业务账号。交接在QA26_FULL2短事务执行并持久化无密码操作审计，私有配置0600原子替换。之后新登录只读重开23份冻结结果全部一致，Chrome109登录及三个页面巡检无pageError；没有为了该登录巡检重新调用模型。

## ARM64离线交付和迁移验收

沿用原update.sh/current/drop-in/回滚机制，内部update-v10标识为向前兼容而保留。新包短名Update_v11.tar.gz，根直接update.sh，不包套包。指定经过26验证的JAR，显式使用新前端和Python源；保留现场模型地址/密钥/模型名、数据库/Redis、Nginx和历史文件。Nginx须指向/opt/audit-agent/current/frontend/dist，不覆盖现场配置。

新检查器在26独立测试Schema完成真实DM8首次11条DDL、重复0条DDL、DDL成功代码回退后重查，以及改比较条件/类型/主键预期、真实约束禁用/默认值/FK顺序漂移和部分组拒绝。全量既有QA Schema只读复查通过。新增活动/UNKNOWN门槛真实DM8七类反例拒绝、两种已完成状态允许，fixture全部回滚。Java17 class major61，最终包重新解压和manifest验证后登记SHA。

正式更新前必须由甲方在内部完成数据库与文件备份及恢复验证。--check保持只读；正式更新要求root0600恢复确认记录和两个备份归档哈希。确认记录只证明现场操作员声明与文件哈希，不能替代真实恢复。守卫用纯合成文件验证1正向/5反向，旧drop-in回滚/重复更新5项Linux沙箱测试通过。没有在原业务库或甲方执行新DDL。

ARM64沿用已有JDK17、Python3.11/PaddleOCR3.7 venv与依赖，不混入x86 wheel、RPM或系统动态库，不联网pip，不更换glibc/openssl。Windows制包和26 x86_64验收不等于麒麟ARM64实际升级成功；完整ARM64 update.sh --check、OCR、模型白名单/协议和客户真实材料仍须现场验证。

## 交付限制与当前状态

未知材料无法保证100%提取准确；可靠部分优先保留，范围不足不作确定结论。新采购自动入口整批FormData上传，尚未接入旧通用分片续传。正常服务合同也可能因个别模型描述缺证据而PARTIAL；不能把它改标COMPLETED。原80入口和原库完整恢复/生产容量、TongWeb/TongRDS/TongHttpServer均未验证。

CONTRACT_DEVELOPMENT=VERIFIED（本报告限定范围）
CONTRACT_E2E_TEST=VERIFIED（合成材料/真实模型、DM8和Chrome109）
GITHUB_PUSH=VERIFIED（4253bb5及11c217d已推送且ls-remote一致；最终文档提交另见回执）
TEST_SERVER_26_DEPLOYMENT=VERIFIED（独立18082，不是原80入口）
ARM64_OFFLINE_PACKAGE=PARTIAL（PACKAGE_BUILT_AND_STATICALLY_VERIFIED；ARM64实际执行NOT VERIFIED）
CUSTOMER_DEPLOYMENT=NOT_EXECUTED

最终Update_v11.tar.gz为84,372,416 bytes，158文件；SHA256为acb6b917131e0586604f7c5b6c5d06b68a93f10b8bf32ff4d7975473d8751be8。最终tar独立解压核对全部manifest且无未登记文件，22个class均Java17 major61；后端与26 JAR、前端与Chrome109候选匹配。最终包内5个Shell脚本Linux bash -n、62个Python文件AST全部通过；无原生wheel/系统安装包及真实配置。桌面Audit_agent仅新增包与外层.sha256。详见PACKAGE_VERIFICATION.json、PACKAGE_TESTS.md及DELIVERY_RECEIPT.md；不能把未验证项当作已验收。
