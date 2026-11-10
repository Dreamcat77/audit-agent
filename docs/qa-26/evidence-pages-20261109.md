# 只展示原文件证据页（2026-11-09）

## 最终交互

用户明确收敛：不要多个展示选择，直接给原文件和高亮，只展示证据页。此约定替代上一轮默认“关键片段”和原文件/文字切换。

三模块相关原文共用弹窗：标题、关闭、来源文件名、原文件证据页及关键位置高亮；没有关键片段/原文件/解析文字/完整引用切换。单证据页只有缩放；同文件多证据页只允许在实际引用的页面之间切换。第2页和第9页为证据时直接2→9，不加载3—8或整份文件后续页面。相同页面去重。显示PDF物理页码，不冒充文档印刷页码。

上一轮短句选择仍仅供内部缩小坐标高亮范围，不再默认展示文字片段或修改原报告。完整引用先按冻结文本验证后派生子偏移；原件真实像素保持。已证明锚点页优先于旧粗略页提示；未知位置不猜第1页，只有页码时允许展示该页但不伪造高亮。不同来源按文件分开。

读取引用每次最多并发3个，不再把超过前6个窗口/前12个引用的证据静默遗漏。原图只按当前证据页延迟读取。切页后图片携带页码及来源身份，避免新高亮短暂覆盖旧页像素。无模式切换后仍保留原先无法预览格式的自动短文字回退：Word/Excel尚未增加原页转换器，不宣称支持所有格式原页。

## 验证

- Windows / JDK17，在backend执行 `./mvnw.cmd clean verify`：3642项，0失败/0错误/24跳过，BUILD SUCCESS。
- frontend下全部 `*.test.ts` 通过872项；新增不连续证据页、过时页提示、无位置不猜第一页及非法页码反例。`npm run build:customer` 成功，CHROME109_COLORS_READY=220。
- Chrome109命令 `node --test frontend/tests/processing-status/processing-status.browser.test.mjs frontend/tests/evidence-viewer/evidence.browser.test.mjs`：19项通过。直接原页/无模式选择、2→9只请求2/9页、单页无文档翻页、长引用只高亮关键子范围、未知页不请求第一页、缩放/重开/正文与标题隔离/12处引用，以及三模块处理状态回归通过。
- `git diff --check`通过。日志均在 `.runtime/evidence-pages-20261109/`。

## 26默认80入口

仅静态更新，UI修订 `evidence-pages-20261109-r1`；release仍 `/opt/audit-agent/releases/evidence-original-20261109-r2`。入口SHA256 `5eb1fc66148cba1f54e8dd7ca2a752e2421a3ae29a3032b7a0f05e39751bfe37`。默认80的7份资产HTTP200且哈希全部一致；backend JAR `d840b3e23246670ea1c7c3e6259da79bc99e4cc06e89d5c988671b71d2abf2d8`、AI源码、配置及服务PID保持。backend/AI健康UP，无DDL和服务重启。

Chrome109实际访问26，三模块既有持久化结果：打开直接原文件，高亮、两种视口边界、关闭重开通过，合同八项仍保留，无页面脚本异常。真实长投标文件“投标函与开标一览表计价口径不一致”只展示第9/11两个证据页，不再浏览整份274页文档；关键位置坐标高亮保留，没有文字模式按钮。实际截图 `browser109-long-price-original.png`，记录 `long-live-verification.json`。

证据 `.runtime/evidence-pages-20261109/{deployment.json,browser-verification.json,long-live-verification.json,backend-tests.json}`。本轮没有调用模型、覆盖历史报告、提交Git或制甲方包。甲方ARM64现场NOT VERIFIED。
