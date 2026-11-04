# 三模块扫描方向识别与模型链路验证（2026-10-22）

维护范围现包括合同、预算、采购及公共链路；范围说明已写入 `AGENTS.md` 和 `.cursor/rules/maintenance-scope.mdc`。本轮不扩建新业务模块。

## 实现边界

- 公共 OCR 纠正页/帧方向、EXIF 方向与倾斜，最终识别一次，不合并多个方向的重复文本。
- 原生 OCR 放入独立单并发子进程；Linux 长期 owner 线程持有该子进程，避免请求线程退出误杀 OCR。UNKNOWN 不自动重放。
- 合同 DOCX 正文、表格、页眉页脚嵌图复用 OCR，读取失败不发布半份解析结果。
- 预算大写/阿拉伯并列金额仅在数值一致时接受；冲突仍拒绝。
- 预算、采购遇到 OCR 忙时保持原任务身份，以 lease fencing 回队；旧执行者不得覆盖新结果。
- 有明确供应商身份的扫描报价单识别为投标人材料，同一家两份文件不冒充两家供应商。
- 采购同来源、同建议的包含关系摘录合并显示，原始风险记录保留。
- 直接合同文字分析及其受控补全分支显式关闭模型思考，保留既有输出预算；正式冻结语义工作通道不携带此入口策略。

## 本地回归

环境：Windows、JDK 17、Python 3.11。运行路径为当前隔离工作树。

- `backend/./mvnw.cmd clean verify`：3139 项，0 failures，0 errors，15 skipped；记录 `.runtime/rotation-final-java.log`。后续提交仅修改 Python，Java 字节未变。
- `services/audit-ai` 下运行 `python -m pytest -q --basetemp=<短临时目录>`：2012 passed，2 skipped，43 subtests passed；记录 `.runtime/rotation-propagation-full.log`。Windows 使用系统 PowerShell 模块路径避免 ACL 检查误失败。
- 前端 `node --experimental-strip-types --test src/features/procurement/review/tenderFindingDisplay.test.ts src/features/procurement/review/procurementReferenceView.test.ts`：11 项通过；`npm run build` 成功。构建仍有既有大 chunk 警告。
- `git diff --check`：通过。

## 测试服务器真实验证

使用已部署应用、真实 DM8、OCR 与商业模型。全部材料为仿真数据；临时测试账号在 runner 的 finally 中禁用，凭据仅经匿名管道传递。保留合成任务及审计记录，不清业务数据。

- `tools/test-rotation-application.py`：r6 七份预算全部通过；正向、左右 90 度、180 度、轻微倾斜均识别 120000/100000；混方向三页识别 48000/40000、36000/30000、36000/30000；Word 嵌图识别 84000/70000。超批复判断及重新登录历史回读通过。
- 同一脚本采购正式整批：r7 四份通过。Word 倒图需求、横向招标 PDF 进入招标审查；横置 JPG、带旋转标记 PDF 识别投标人。品牌严重风险、地域一般风险被发现；同供应商两份报价不生成虚假围串标评分。原文件 SHA、重新登录报告 ID 一致。
- `tools/test-contract-rotation-application.py`：r6 倒置 PNG、横向 PDF、正文嵌横图 DOCX 三份通过。原 SHA 和持久化解析结果保留，识别 120000、6000、30 日、三年、万分之五及验收事实；真实模型返回摘要、提示及证据。
- `tools/test-contract-result-quality.py`：r8 三份返回完整描述，原文与 parseResultId 未变。完整描述检查通过只代表文字链路可用，不代表所有业务判断正确。
- `tools/test-linux-ocr-owner.py`：真实 Linux 验证 `LINUX_OCR_NATIVE_GIL_CLOSE_TIMEOUT_CRASH_PARENT_DEATH_NO_REPLAY=PASS`，覆盖 GIL 隔离、请求线程退休、关闭、崩溃、超时、父进程退出及 UNKNOWN 不重放。
- `tools/OcrQueuePlatformCheck.java`：真实 DM8 验证 `DM8_OCR_QUEUE_REQUEUE_IDENTITY_STALE_LEASE_UNKNOWN_NO_REPLAY=PASS`；探针事务回滚，未留业务测试行。

## 已知限制与未验收范围

- 合同旧文字分析入口仍存在偏乐观表述：原文仅载明盖章生效，个别模型结果标题称“生效及终止条件明确”；部分“完整、无明显风险”判断超出了已确认事实。此类结果不得当成正式合同审查质量验收通过。
- 正式合同五步 ReviewRun：`NOT VERIFIED`。文字提取、模型 HTTP 成功或完整描述不能替代其持久化全流程验收。
- 甲方 ARM 离线现场、其他低版本浏览器的全部页面视觉效果：`NOT VERIFIED`。
- Chrome 109 检查仅覆盖登录入口、页面脚本错误和健康接口，不代表全页面视觉验收。

## 最后候选

应用候选 commit：`ffb86310dc8e0a22309a9811cad7a5b74931cd6f`。

内部更新归档 SHA-256：`cb5c12e31b2d16e1491a7e04b972af739980b62f2d0719d86f64da3602f00a1e`。

r9 不修改 schema；继承现有方向模型、OCR worker 配置和中间件配置。

最终部署命令：`sudo bash /home/admin/rotation-all-r9/tools/deploy.sh`。观察结果：`BUDGET_PROCUREMENT_DEPLOY26=PASS`、`CONFIG_HASHES=UNCHANGED`、`HTTP_ASSETS=3`；结构预检 statements=0。实际 release 为 `/opt/audit-agent/releases/rotation-all-20261022.TVK6Kv`，回滚记录 `/opt/audit-agent/update-backups/rotation-all-20261022.uyRLVN`。

实际运行后端 SHA-256：`4b0f5743304c178cde2fa56c8dd1b222b7ff6f141ef34072d4406d07ab100bb1`；首页 SHA-256：`cbea230866a44ae5905c28b96a210875cc17d2d81b8ae622a8c7c728253a6a7f`。

r9 通过 `BudgetBackgroundRunner` 的匿名管道临时账号运行 `tools/test-contract-result-quality.py`，复用 r6 已保存的三份合同：三份完整描述/来源不变检查通过，unit 退出码 0、inactive，临时账号已禁用。真实日志中 ANALYZE 和 EVIDENCE_REPAIR 都为 `thinking_disabled=YES`。上述已知业务判断局限仍然存在，脚本输出的 QUALITY=PASS 仅指该脚本明确断言的范围。

`node .runtime/rotation-entry109.mjs`：真实 Chrome `109.0.5412.0`，首页 HTTP 200、健康 HTTP 200 且 `status=UP`、`errors=[]`。三个服务 active。
