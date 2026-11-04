# 合同“查看原文”定位与去重

保留原有分析、上传、审批流程。每条意见仍使用“查看原文”，一次展示该意见的相关文件和位置；不再为同一意见内每个重复引用生成一个按钮。

- 同一文件、同一范围、同一原文的引用只显示一次。相同文字位于不同范围、不同文件或不同已知页段时保留各自位置。
- 重叠引用与相交上下文窗口合并，避免反复显示同一段。相距较远的引用分开显示，不夹带中间无关页面。每处默认只带前后各90字符上下文。
- 用服务端已验证的原文范围高亮；再次核对返回窗口的起点和引用逐字内容。发生变更、范围错位或引用不一致时不猜测其他同文位置，也不高亮错误文字。某处读取失败不影响其他证据。
- 已有页码、段落号保持显示；缺少精确位置时诚实展示引用，取消原先默认打开文件前1200字符的做法。不会根据意见中的关键词虚构定位。
- 每次最多显示6个窗口，可继续查看其余引用；同时读取不超过3个窗口，已读取窗口在本弹窗内复用。关闭或切换记录后不接收过期读取结果。支持Escape关闭并恢复按钮焦点。
- 模型提示词进一步要求直接支持判断的最小完整句群，保留责任方、触发条件及例外；多个位置分别短引，不重复同一位置。不改写历史证据，也不截断证据后冒充完整依据。

验证（2026-11-04）：

```bash
cd frontend
node --test src/features/contract/contractAnalysisEvidenceView.test.ts src/features/contract/contractAnalysisPresentation.test.ts src/features/contract/contractEvidenceNavigationRegression.test.ts
node --test tests/evidence-viewer/evidence.browser.test.mjs
```

25项Node测试及5项Chromium组件浏览器测试通过，覆盖6条引用归并为3处高亮、跨文件、多处位置、错误范围、缺少定位、关闭及焦点恢复，以及分页复用和单处失败不影响其他材料。Python合同分析/身份脱敏相关38项测试通过。后端完整`clean verify`仍有1项既有前端client.ts固定哈希守卫失败，其他3425项通过、24项跳过；该文件未修改。

完整前端`npm ci`因网络策略拒绝`cdn.sheetjs.com`失败，该域名是已有锁文件中xlsx 0.20.3的官方来源。需要在环境设置审阅、保存相应域名配置，并发布环境，再重跑`npm ci`及`npm run build`。不要替换xlsx版本、改锁文件或关闭校验绕过。相关域名已保存到云环境配置草稿，保存草稿不等于运行环境已应用。

为继续独立验证，本次在忽略的`.runtime/evidence-preview`安装了仓库相同版本的Vite、React和Playwright工具，使用实际新增组件执行浏览器测试及组件打包；并编译检查工作区TSX。此结果不是完整应用构建或客户环境验收。可通过`AUDIT_BROWSER_TEST_NODE_MODULES`指定该独立工具目录；常规依赖安装完整后直接运行上面的命令即可。浏览器路径可用`AUDIT_BROWSER_EXECUTABLE`指定。
