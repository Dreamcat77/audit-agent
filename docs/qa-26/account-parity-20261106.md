# 2026-11-06 账号功能一致与个人上传隔离

## 需求与修复

采购、预算、合同角色只是账号标签，不限制当前业务工具。移除菜单、工作台辅助工具和统计卡片的个人角色裁剪。test_admin、caigou、yusuan、hetong 均显示工作台、预算审查、采购审查、合同审核、黑名单、风险预估。

开放统计和黑名单查询时同时补全结果归属约束：任务列表在分页和计数之前按持久化项目上传者筛选；采购报警按持久化采购批次所属账号筛选，覆盖明细、分页、导出和处理操作。无明确归属的历史结果不分配给个人账号，混合旧管理员角色不能绕过限制。黑名单参考数据和规则仍是共享业务配置。

不追加 SYSTEM_ADMIN，不增加角色分配页面，不更改账号密码，无数据库迁移、不清理历史业务数据。

## 改动文件

- `frontend/src/app/App.tsx`
- `frontend/src/features/dashboard/DeliveryWorkbenchPage.tsx`
- `backend/src/main/java/com/fuquan/audit/contract/api/ContractAuditTaskController.java`
- `backend/src/main/java/com/fuquan/audit/contract/application/ContractAuditTaskListService.java`
- `backend/src/main/java/com/fuquan/audit/security/infra/PersonalUploadAccessInterceptor.java`
- `backend/src/main/java/com/fuquan/audit/security/infra/PersonalUploadWebConfiguration.java`
- `backend/src/main/java/com/fuquan/audit/security/application/port/UploadOwnershipPort.java`
- `backend/src/main/java/com/fuquan/audit/integration/BusinessUploadOwnershipAdapter.java`
- `backend/src/main/java/com/fuquan/audit/blacklist/api/BlacklistController.java`
- `backend/src/main/java/com/fuquan/audit/blacklist/application/BlacklistQueryService.java`
- 对应前后端回归测试及 `docs/personal-upload-roles.md`。

## 构建与发布

- 本机 JDK17，backend `./mvnw.cmd clean verify`：3581 项、0 失败、0 错误、24 跳过。
- 前端 Node 测试 863 项通过，customer 构建和 Chrome109 颜色处理通过。
- 共用原文弹窗 Chrome109 浏览器 7 项通过，包含单页、12 页、顶部/中部/底部、黄色高亮、小窗口和关闭重开。
- 本次相关文件 `git diff --check` 通过。
- 26 release：`account-parity-20261106-r1`。
- JAR SHA256：`d9ffca7ebe8c1761ecaf35217ac433db166ca74c2dd1d50d35a05901068446f2`。
- index SHA256：`cefb3fb2005c6b5852680c91624a2ccfcc78f4b2998ae9482281bd450002f057`。
- 等已有采购任务自然结束后切换，仅重启 Java 后端；配置、AI 服务和历史数据保留。DM8 活动任务检查通过，8001/8080/80 健康检查和所有前端发布文件哈希通过。

## 26 Chrome109 实机验收

四账号的六个页面、相同工作台工具/统计、黑名单各页签、任务查询、黑名单查询/导出及兼容别名均 HTTP200；未出现页面脚本错误或登录后的接口错误。

三个人账号分别上传预算、采购、合同合成材料，42 次跨账号资源读取和 6 次跨账号预算确认均返回404。三账号预算均 PARSED；合同均 COMPLETED、可读取八大项和已解析原文；采购均生成 DATA_INSUFFICIENT 报告，本轮特意使用合同合成材料验证有结果收尾，不把这些材料当成报价合规通过。

三账号预算/采购/合同历史均仅含本人本轮批次，合同结果重开后八大项可见且没有上传入口，本人合同原件下载及采购 HTML 报告 HTTP200。Chrome109 无脚本错误。最终记录 `verification.json` 的 status=PASS。本轮未评价真实客户材料的模型准确性。

原文弹窗另外使用26已有真实批次完成验证：3 处逐字高亮；1000/440/320px 视口高度分别滚动顶部、中部、底部，正文始终在标题下面，只有 Body 滚动；关闭、重开和焦点恢复正常。此验证只读取已保存结果，不触发新模型调用。

证据目录：`.runtime/account-parity-20261106/`，包括 `deployment.json`、`source-verification.json`、测试日志和 Chrome109 截图。账号密码仅交互输入并保留在进程内存，不进入脚本或证据。

本轮未提交 Git、未制作甲方更新包。甲方 ARM64 环境 NOT VERIFIED。
