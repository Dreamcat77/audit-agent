# 历史分析列表宽度微调

仅修改 `frontend/src/features/budget/f05/BudgetProjectRiskPanel.tsx` 的栅格列宽：原 xl 侧栏 280px 改为 360px，2xl 侧栏 400px。窄屏仍为上下布局；查询、分页、合计、历史保存行为不变。

验证：

- frontend `npm run build` 成功。
- backend `./mvnw.cmd clean verify`：2282 tests，0 failures，0 errors，2 skipped，BUILD SUCCESS；日志 `.runtime/budget-history-width-v23-verify.log`。本次后端构建产物未部署。
- `node tools/build-procurement-browser.mjs frontend/dist .runtime/budget-history-width-v23-browser` 成功，保留原样式及 221 处 Chrome 109 颜色回退。
- `node tools/test-budget-material-background.mjs <browser-executable> .runtime/budget-history-width-v23-browser <label>`：Chrome 109.0.5412.0 和 Chromium 151.0.7922.34 均 exit 0，errors=[]。最终 Chrome 109 截图已检查：`.runtime/budget-background-browser/chrome109-history-width-v23-search-pagination.png`。
- `git diff --check` 通过。

26 前端已发布，`BUDGET_HISTORY_WIDTH_TEST26_DEPLOY=PASS`；入口及 3 个直接引用资源 HTTP 与磁盘文件逐字节匹配；健康接口返回 HTTP 200、status UP。配置哈希不变，未替换后端、未重启服务。

- release：`/opt/audit-agent/releases/budget-history-v23-20261016.iZPLJ7`。
- 回滚记录：`/opt/audit-agent/update-backups/budget-history-v23-20261016.FP3r2h`。
- 前端归档 SHA-256：`d7f6917d465d4b5795805a7128ae24bd86c681bee4baa99e53a143ed13c1861f`。

甲方现场：NOT VERIFIED。本次未提交或推送 Git。
