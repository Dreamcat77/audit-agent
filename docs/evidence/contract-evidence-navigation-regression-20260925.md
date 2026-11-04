# 合同原文定位回归记录（2026-09-25）

状态：AUTOMATED PASS / REPRESENTATIVE REAL PASS / F02 SOURCE LIMIT DOCUMENTED

## L01—L12

| 编号 | 场景 | 自动化 | fresh 真实环境 |
| --- | --- | --- | --- |
| L01 | 唯一文本选择 exact file 并精确高亮 | PASS | PASS：01 三个来源点击均选中正确 file，highlightCount=1 |
| L02 | 重复文本且有 paragraph locator 时按段落定位 | PASS | 本轮未单独制造重复文本原件场景 |
| L03 | PDF page 绑定 frozen source anchor | PASS | 真实 PDF 材料可切换；页级场景未单独逐页记录 |
| L04 | 同一文件两个位置保持两个来源身份 | PASS | 自动化证据 |
| L05 | 合同/采购/响应等双侧来源分别打开对应材料 | PASS | PASS：01 合同、响应、采购三侧分别点击成功 |
| L06 | 多来源条款保留每个独立可点击材料 | PASS | PASS：01 跨文件来源保留且可点击 |
| L07 | live 同名文件不能替代 historical frozen tuple | PASS | exact run 后端重启重开保持 frozen input |
| L08 | 重复文本无可靠段落时返回 ambiguous | PASS | 自动化证据；没有伪造唯一高亮 |
| L09 | locator unavailable 时明确提示并仅打开 exact file | PASS | F02 结构化人工事实明确 SOURCE_UNAVAILABLE |
| L10 | exact run 切换清空旧 source focus | PASS | 第二浏览器 exact run 重开通过；迟到 focus 仍由自动化覆盖 |
| L11 | 迟到的旧 run focus 不能污染当前 run | PASS | 自动化证据 |
| L12 | historical mode 不回退 current/live 文件 | PASS | 项目入口、历史入口、刷新、后端重启后均保持 exact run |

自动化来源为 contractEvidenceNavigationRegression.test.ts，L01—L12 全部通过。真实点击使用 run 515ffc90-90d5-4ee6-87ea-9f16b87e456f，没有修改原件或历史记录。

## F02 定位载体

- F02 业务结论继续由 AuditTask/AuditCheckResult 权威持久化。
- navigation v2 使用 checkResultId 到 deterministic findingId，再到 evidence/relation 的稳定身份；不靠摘要文本或数组位置关联。
- 每个真实 run 有 8 条 checkResult、8 条 finding 和 8 条 relation。多行 LINE_ITEM_ARITHMETIC 全部保留。
- 本轮 F02 的正式 Contract 数值来自人工 Confirmation，现有链路没有可证明的原文 locator，因此 evidence 数为 0。
- locator 缺失时记录 SOURCE_UNAVAILABLE，不伪造 excerpt、PDF page、offset 或 bbox，也不把 live Candidate/support 投影当 frozen evidence。
- legacy run 的空 evidence 继续合法；新 v2 输出缺失稳定关系时 fail closed，不静默冒充旧格式成功。

## 跨文件与历史实证

1. 在 01 的八项工作区展开问题并点击来源。
2. 合同依据选中正式合同文件，响应依据选中响应文件，采购依据选中采购文件。
3. 三次均观察到正确材料 tab/fileId 和 highlightCount=1。
4. 刷新页面、通过历史入口、第二浏览器和 backend restart 后，仍重开同一 exact run 与 frozen material tuple。
5. 05 的异项目通知被人工确认 NOT_SAME_PROJECT 后，最终 run 只冻结 4 份同项目材料；被排除文件没有进入右侧来源选择。
6. 匿名 exact-run GET 返回 401。没有第二个已授权低权限账号，所以跨用户 owner 真实定位拒绝仍为 NOT VERIFIED。

## 回归门禁

- Frontend default runner：641/641。
- Frontend shared runner：641/641。
- Backend clean verify：1831 tests，0 failure/error，2 skipped。
- reviewRun F02 adapter、mapper/read/recovery、old/new coexistence 与 frozen reopen 均在上述门禁中通过。
- 当前未发现原文定位从精确 anchor 退化为只打开文件首页的回归。

结论：已有可靠 locator 的问题具备自动化与代表性真实点击证据；F02 人工正式事实的 locator 缺口被诚实展示，属于当前 provenance 限制。
