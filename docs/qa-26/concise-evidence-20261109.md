# 简短关键证据展示（2026-11-09）

## 改动及边界

此前共用弹窗默认展示原文件整页，按持久化引用的完整范围叠加坐标。采购部分引用覆盖整页，造成大范围标黄、关键报价难找。预算、采购、合同现在默认先展示“关键片段”：每段不超过220个UTF16字符；文件名与已知页码保留；按点击问题的标题/说明选取原引用中的相关句子，长句作有界截取，短引用原样保留。金额单位、否定、后续例外条件回归覆盖。

片段仅为展示摘录，不是模型重写，也不修改原引用、审查结论、数据库或原文件。先按冻结文本完整核对原引用位置，再从已核对的连续区间提取短句并计算子区间偏移。无法核对的内容不标记已验证；无位置的引用只展示引用文字，不能从其他页相同词语猜位置。取句属于展示启发式，不能据此声称所有复杂条款都已获得最优语义摘要。

“查看原页”只定位所选短句并高亮其原页区域；“完整引用”及“解析文字”仍可看完整上下文。不同文件的证据分开展示。保留缩放、页码、页导航、键盘、焦点恢复及独立正文滚动，不增加模型调用。

主要源码：`conciseEvidence.ts`、`AnalysisEvidenceDialog.tsx`、`ProcurementEvidenceDialog.tsx`；采购/合同/预算结果页和合同八项向弹窗传递对应问题文字，共用样式在 `source-evidence-dialog.css`。

## 验证

- Windows JDK17，backend执行 `./mvnw.cmd clean verify`：3642项，0失败/0错误/24跳过，BUILD SUCCESS。日志 `.runtime/concise-evidence-20261109/backend-verify.log`。
- frontend全部Node测试870项通过（包括新增5项原文短句回归），customer构建成功，Chrome109颜色兼容处理220处。日志同目录 `frontend-tests.log` / `build.log`。
- Chrome109执行 `node --test frontend/tests/processing-status/processing-status.browser.test.mjs frontend/tests/evidence-viewer/evidence.browser.test.mjs`：17项全部通过。新增整页报价引用变短句、精确子偏移请求、高亮、完整引用及重开；原有错误偏移、无位置、原页、12页滚动、独立Header、三模块处理中状态均通过。首次与构建并行时dist被重建造成CSS读取失败；等待构建结束后按依赖顺序完整重跑通过。
- git diff --check通过。

## 26默认入口

静态修订 `concise-evidence-20261109-r1`，release仍 `/opt/audit-agent/releases/evidence-original-20261109-r2`。入口SHA256 `49a7f66ac0cbf01b858a866b44e91051bf175067fcceacbff5933d708ecbd643`。7份静态资源在默认80端口HTTP200且逐项哈希一致；backend JAR `d840b3e23246670ea1c7c3e6259da79bc99e4cc06e89d5c988671b71d2abf2d8`、AI源码、配置及服务PID保持不变，无DDL，无服务重启。backend健康HTTP200/UP，AI健康HTTP200/UP。

Chrome109实际访问26，用既有持久化结果验证三模块：默认短句、原页/高亮、解析文字、关闭重开、小视口不溢出、合同八项以及三模块处理中统一状态均通过，无页面脚本错误。例：合同金额证据23字、采购条款证据81字、预算证据18字；均为原引用原样子串。

另从真实历史记录找到“投标函与开标一览表计价口径不一致”（case `2dbd4cdb-ad9c-3be1-b087-6abe5e919d0a`）：原两处529/341字引用，默认缩为投标年度报价句及开标一览表优先约定，保留“贰佰贰拾万元人民币/年”。两段均逐字属于原引用，原报告未重跑或覆盖。截图 `browser109-long-price-snippet.png`，结果 `long-live-verification.json`。取句并非重新审核该问题结论。

证据 `.runtime/concise-evidence-20261109/{deployment.json,browser-verification.json,long-live-verification.json}`。本轮未提交Git、未制甲方包；甲方ARM64现场NOT VERIFIED。
