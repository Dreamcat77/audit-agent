# 预算上传批量后台处理验收（2026-10-10）

用户要求桌面两个测试文件夹，并让预算上传采用采购的批量处理逻辑，仅小改前端。桌面真实位置为`D:/UserData/Desktop`。`采购测试文件`、`预算测试文件`分别复制既有12份合成DOCX、12份合成XLSX，逐份SHA-256校验一致，没有覆盖既有不同文件。采购总28,703,977字节，含约27MB材料；预算总687,912字节、每份80行和较长科目名称。这些是合成测试材料，不含真实业务数据；预算测试不是12份大容量二进制文件。

修改范围：预算页面保留原页面结构、配色、规则展示和结果页；上传区支持多选、递归目录拖放、500MB单文件上限、逐文件失败隔离。仅增加本批数量、成功/失败文件名及多成功结果切换下拉框。删除原按时间伪造完成步骤，显示真实后台状态。复用采购批次队列和目录读取器，参数化类型和本地存储命名空间；预算操作身份与采购隔离。预算年度和部门保存在原操作元数据，刷新/重选原文件不会改变不确定操作身份。结果、材料内容不写入浏览器持久存储。

新增预算专用后台材料API、应用服务、短事务协调器、单worker、DM8/内存端口及V051队列表。队列ID是真实BudgetReviewTask ID，唯一(actor,key)及内容hash防重。文件先保存再入队，任务/入队/审计原子提交；队列claim与完成受token保护，短结果事务锁定租约行，OCR/模型/解析不在数据库长事务内。后台心跳、过期任务发现、重启恢复，不依赖Redis队列。停机线程中断保留PROCESSING供过期恢复，存储/数据库不确定状态为UNKNOWN。原预算金额规则和异常确认保留，一份WAITING_CONFIRMATION不影响其余文件。没有新增预算DOCX或扫描PDF OCR能力。

主要文件：`frontend/src/app/BudgetCheckPage.tsx`、`frontend/src/features/budget/budgetMaterialBatchStore.ts`、采购批次通用化代码、App后台根挂载；`backend/.../budget/{api,application,domain,infra}`新增预算后台材料类；`integration/dm8/Dm8BudgetMaterialJobRepository.java`；`BudgetReviewService`和`BudgetReviewTransactionCoordinator`结果提交租约校验；预算操作审计模块标识；`db/dm8/V051__budget_material_background.sql`和迁移工具；离线更新/回滚脚本及打包工具。大量既有工作区改动保留，没有提交或推送。

Windows已有JDK17.0.18/Node24：

```powershell
# backend
.\mvnw.cmd clean verify
# frontend
$tests=rg --files src | Where-Object {$_ -match '\.test\.ts$'}
node --experimental-strip-types --test @tests
npm run build
# repo
node tools/build-procurement-browser.mjs frontend/dist .runtime/budget-background-frontend-final
node tools/test-budget-material-background.mjs E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe .runtime/budget-background-frontend-final budget-final-109
node tools/test-budget-material-background.mjs 'C:/Program Files/Google/Chrome/Application/chrome.exe' .runtime/budget-background-frontend-final budget-final-current
node tools/test-procurement-material-background.mjs E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe .runtime/budget-background-frontend-final budget-procurement-final-109
git diff --check
```

最终Java2182项、0失败、0错误、2 skipped、BUILD SUCCESS；前端722项全部通过、构建成功。日志`.runtime/budget-background-backend-verify-final.log`、`budget-background-front-tests-final.log`、`budget-background-front-build-final.log`。覆盖CSRF/角色/文件所有者、同key内容不一致、入队先返回、损坏文件/待确认文件不阻塞其他材料、过期租约接管与陈旧结果拦截、停机中断保留可恢复任务，前端预算/采购身份隔离和冻结年度/部门。

Chrome109.0.5412.0及Chrome155.0.8059.39真实构建配合明确HTTP替身：12份目录拖放（含21MB文件）、首文件失败继续、丢失上传响应恢复、离页上传和刷新恢复相同操作ID、简短成功失败文件名及结果切换通过，无页面错误。采购原12文件回归、表头字体、后台继续、清空旧响应隔离继续通过。截图/报告`.runtime/budget-background-browser/budget-final-109*.png/json`及current；采购报告`.runtime/async-material-browser/budget-procurement-final-109.json`。浏览器替身测试不声称已验证真实大文件预算解析。

26真实Kylin/DM8：V051首次迁移前后历史预算任务184条不变；manifest/脚本语法/HTTP静态资源哈希通过，原后端/AI环境与Nginx配置SHA一致。DM8双连接持有FOR UPDATE锁时接管更新被阻塞，提交释放后新token接管成功，旧token续租/写回被拒绝，终态不再自动重试；精确合成队列行清理，无业务行改动。命令`/opt/java/current/bin/java -cp '.:/home/admin/batch12-20261010/jar/BOOT-INF/lib/*' BudgetQueuePlatformCheck`，结果`REAL_DM8_BUDGET_QUEUE_TWO_SESSION_LOCK_EXPIRED_CLAIM_STALE_RESULTS=PASS`。证据`/home/admin/batch12-20261010/budget-queue-platform-result.log`。

v2代码真实HTTP/DM8批次：Java合成临时账号经匿名管道传随机凭据给HTTP测试，上传12份既有预算XLSX和1份损坏XLSX，首次响应即入队；同key/同内容重放返回原任务；断开会话后服务端继续，新会话恢复全部任务。最终PARSED12、FAILED1、UNKNOWN0、每份有效材料80行，任务结果为原预算WAITING_CONFIRMATION/REVIEWED，可从原结果API读取。合成账号测试后禁用，审计和合成任务保留。命令`BudgetBackgroundRunner /home/admin/batch12-20261010/test-budget-background-http.py`；证据`budget-background-http-result.json`、`budget-background-http.log`。v3只追加停机中断恢复检查，最终全量门禁及中断回归通过。

最终v3更新包`.runtime/budget-background-delivery-final-v3/AuditAgent_Budget_Background_20261010.tar.gz`，70,861,023字节，SHA-256 `896f22e0d58add82cc326846ed799b8024f8837e7b63b1f73359b56d215ea749`。已复制桌面`Audit_agent/AuditAgent_Budget_Background_20261010.tar.gz`并比对哈希。仅Java后端、前端、迁移和脚本，没有AI源代码、模型、环境或业务文件。未知/漂移schema拒绝更新，保留原代码回滚和队列；仅重启Java，不重启AI或Nginx。26跳过旧安装marker检查的服务器适配不在交付归档内。

甲方ARM64实机浏览器/甲方本地模型/甲方DM8迁移：NOT VERIFIED。500MB为上传上限，本次真实预算解析样本约57KB/份；21MB仅覆盖浏览器上传路径，不代表已验证500MB工作簿解析资源上限。

最终v3已部署至26的`/opt/audit-agent/releases/budget-background-20261010.YqsRoH`，迁移重复执行识别ALREADY_MATCHING，197条任务前后不变。最终更新日志`/home/admin/batch12-20261010/budget-final3-update.log`，恢复目录`/opt/audit-agent/update-backups/budget-background-20261010.XsjTb8`。包SHA、manifest、脚本语法、前端全部HTTP资源hash、未登录预算接口401及原环境配置hash校验通过，backend/ai/nginx均active，真实`curl -fsS http://127.0.0.1/api/v1/health`返回`{"code":200,"message":"success","data":{"status":"UP","service":"audit-backend"}}`。最终版本另用1份正常80行预算+1份损坏材料复验真实API：PARSED1/FAILED1/UNKNOWN0，关闭会话后继续、同操作重放、重开查询均通过。日志`budget-background-final3-smoke.log`、报告`budget-background-http-smoke.json`。随机测试账号已禁用，合成任务及审计保留。未实际执行交付回滚脚本，回滚运行验证为NOT VERIFIED；正常更新和自动版本保护检查已执行。
