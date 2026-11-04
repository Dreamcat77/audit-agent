# 第三阶段证据索引

日期：2026-10-27。实际工作树：`E:\audit-agent-new\.runtime\budget-home-e17-20261014`。

以 IMPLEMENTATION_REPORT.md 为验收结论；本目录证据不将Mock、真实模型、真实DM8和正式部署混同。最终12文件案件为 dd79a596-5f85-3c67-b304-57b54962f07a；付款矛盾案件为7fb732bc-55ad-3127-91c3-b940882a7d30（合成两文件）。

## 使用与完整性

- 主报告：`IMPLEMENTATION_REPORT.md`；公开证据：`evidence/`。
- 本轮源码差异：`phase3-vs-phase2-source.diff`，不能用HEAD差异替代。
- 原始失败尝试保留；以文件描述区分首轮失败与最终通过。
- `DELIVERY_SHA256.json`包含报告、索引和证据逐文件SHA；不包含自身、受限口令/cookies或完整数据库导出。
- 口令与认证配置仅受限运行目录保留，不属于交付证据；重新执行须注入自己的隔离环境信息。

## 命令与环境

- Windows/JDK17：backend下 `.\mvnw.cmd clean verify "-Daudit.build.directory=target-phase3-final"`；14历史失败保留。
- 独立AUTOCASE2：backend下 `.\mvnw.cmd "-Daudit.build.directory=target-phase3" "-Dtest=Dm8AutoCaseRealIntegrationTest,CaseReviewDm8RealIntegrationTest" test`，真实6项通过。
- Python3.11/FastAPI/httpx：runpy调用两个测试文件共7个无fixture函数；非全Python suite。
- frontend下 `npm run build`；浏览器执行 `node tools/automatic-case-phase3/browser-acceptance.mjs`，配置经stdin注入。
- `acceptance_runner.py`运行真实curl/HTTP、独立数据库/Worker及受控浏览器场景；`scenarios.py`包含语义引用校验、强杀/重开和真实模型对抗。不得指向原业务库。

## 文件说明

- [evidence/actual-http-conflict-result.json](evidence/actual-http-conflict-result.json)：真实模型/DM8两文件付款冲突发现与双文件原文；整体DATA_INSUFFICIENT。
- [evidence/actual-http-conflict-upload.json](evidence/actual-http-conflict-upload.json)：合成两文件对抗样本真实HTTP创建响应，不是12文件baseline。
- [evidence/actual-model-conflict-fixture.json](evidence/actual-model-conflict-fixture.json)：另行直接真实AI API对抗测试，非完整案件、非12文件baseline。
- [evidence/browser-acceptance.json](evidence/browser-acceptance.json)：真实Chromium上传/刷新/下载/失败操作key验收，无pageErrors。
- [evidence/browser-completed-evidence.png](evidence/browser-completed-evidence.png)：最终完成与证据界面截图。
- [evidence/browser-cross-file-finding.json](evidence/browser-cross-file-finding.json)：真实浏览器风险和两个原始来源验证。
- [evidence/browser-cross-file-finding.png](evidence/browser-cross-file-finding.png)：付款冲突风险和双文件逐字证据截图。
- [evidence/browser-dm8-actual-evidence.json](evidence/browser-dm8-actual-evidence.json)：最终12文件逐字原文、事实、locator和文件SHA。
- [evidence/browser-dm8-actual-restart-reopen.json](evidence/browser-dm8-actual-restart-reopen.json)：最终真实模型案件在Java重启后结果完全一致。
- [evidence/browser-dm8-actual-result.json](evidence/browser-dm8-actual-result.json)：最终12文件真实模型+真实DM8完整HTTP结果；三模块COMPLETED。
- [evidence/browser-dm8-actual-semantic-validation.json](evidence/browser-dm8-actual-semantic-validation.json)：最终业务值、66处引用、02技术全文及scope核验。
- [evidence/browser-f10-semantic-analysis.png](evidence/browser-f10-semantic-analysis.png)：02技术需求与05招标技术要求、F10语义分析截图。
- [evidence/browser-failed-upload.png](evidence/browser-failed-upload.png)：上传失败不冒充成功截图。
- [evidence/browser-partial-module.json](evidence/browser-partial-module.json)：真实浏览器部分完成/未知结果保留验证。
- [evidence/browser-partial-module.png](evidence/browser-partial-module.png)：部分完成/未知界面截图。
- [evidence/browser-processing.png](evidence/browser-processing.png)：12文件自动处理进度截图。
- [evidence/browser-semantic-review.json](evidence/browser-semantic-review.json)：真实浏览器展开技术/评分/付款原文。
- [evidence/dm8-actual-restart-reopen.json](evidence/dm8-actual-restart-reopen.json)：首轮部分结果重启后保持原状态，非最终完成案件。
- [evidence/dm8-actual-result.json](evidence/dm8-actual-result.json)：首轮真实模型通用60秒超时，保留PARTIAL/UNKNOWN失败证据。
- [evidence/dm8-actual-semantic-validation.json](evidence/dm8-actual-semantic-validation.json)：首轮部分结果校验，不代表最终成功。
- [evidence/dm8-actual-upload.json](evidence/dm8-actual-upload.json)：首轮真实curl上传12文件响应。
- [evidence/dm8-mock-evidence.json](evidence/dm8-mock-evidence.json)：Mock模型案件的原始文件证据。
- [evidence/dm8-mock-result.json](evidence/dm8-mock-result.json)：Mock模型真实DM8链路结果；模型明确MOCK_CASE_REVIEW。
- [evidence/dm8-mock-semantic-validation.json](evidence/dm8-mock-semantic-validation.json)：Mock链路语义/引用核验。
- [evidence/dm8-mock-upload.json](evidence/dm8-mock-upload.json)：Mock模型+真实HTTP/DM8的12文件上传响应。
- [evidence/environment-finalization.json](evidence/environment-finalization.json)：本轮独立实例停止、数据保留、原服务仍运行且未被停止。
- [evidence/final-git-status.txt](evidence/final-git-status.txt)：完整共享工作树最终短状态；其中包含既有修改。
- [evidence/final-scoped-tracked.diff](evidence/final-scoped-tracked.diff)：部分tracked文件相对HEAD差异，可能含历史修改；不用于界定本轮全部工作。
- [evidence/final-verification-summary.json](evidence/final-verification-summary.json)：全仓3208项/14历史失败及分类，和二期集合完全一致。
- [evidence/frontend-build.log](evidence/frontend-build.log)：npm run build通过，原有体积warning保留。
- [evidence/full-java-verify.log](evidence/full-java-verify.log)：完整clean verify原始日志；全仓非绿色，不冒称通过。
- [evidence/implementation-boundaries.json](evidence/implementation-boundaries.json)：隔离实施边界记录。
- [evidence/initial-browser-attempt-result.json](evidence/initial-browser-attempt-result.json)：旧超时配置下浏览器首尝试部分结果，未改写为通过。
- [evidence/initial-targeted-tests.log](evidence/initial-targeted-tests.log)：早期针对性回归日志，保留演进过程。
- [evidence/module-targeted-tests.log](evidence/module-targeted-tests.log)：新模块针对性单测日志。
- [evidence/package-build-outcome.json](evidence/package-build-outcome.json)：最终打包退出0、JAR字节数和SHA；不证明测试通过。
- [evidence/package-build.log](evidence/package-build.log)：最终跳过测试的打包原始输出（-q可能为空）。
- [evidence/package-shell-argument-failure.log](evidence/package-shell-argument-failure.log)：补录打包首次PowerShell参数拆分错误，保留后纠正。
- [evidence/phase3-source-manifest.json](evidence/phase3-source-manifest.json)：28源码/测试/工具变更的before/after SHA，二期源文件匹配。
- [evidence/phase3-vs-phase2-source.diff](evidence/phase3-vs-phase2-source.diff)：本轮相对三期前保护副本的准确差异，含新增文件。
- [evidence/python-targeted-tests.log](evidence/python-targeted-tests.log)：Python7个针对性函数测试通过，非pytest全suite。
- [evidence/real-dm8-junit.log](evidence/real-dm8-junit.log)：单独启用真实DM8的6项测试，0失败、0跳过。
- [evidence/restart-interrupted-after.json](evidence/restart-interrupted-after.json)：重启后PRECHECK UNKNOWN、F10/QUOTE完成、整案PARTIAL。
- [evidence/restart-interrupted-before.json](evidence/restart-interrupted-before.json)：真实Java/DM8+Mock模型gate，强杀前RUNNING持久化。
- [evidence/source-protection-summary.json](evidence/source-protection-summary.json)：4728文件备份，4719原文件保留，0意外改动、0删除、0DDL。
