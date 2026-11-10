# GitHub 前端融合与26默认入口复核（2026-11-09）

## 原因与改动

此前融合只覆盖到 `41c60c5eb5d699cbc37f70ac2c2d884019dd4dfd`。另一个工作区随后推送到 `origin/feat/client-review-perspective` 的两次提交没有进入26发布：

- `104a6c90`：三模块统一处理状态，处理中暂不显示材料操作与不完整结果。
- `0f61b868575482574c593c646a1ff5f9228cd157`：状态区域去掉边框、背景和阴影。

26原入口SHA256为 `2bda2d8dc1169828d962f056ed3c8d10d25b9ddb5877798f2f14efbaa4453242`，运行静态资源确实缺少上述提交；不是仅由浏览器缓存造成。

以公共祖先41c60c5e进行三方融合，保留本地后续业务和证据功能。15个文件中3处实际冲突已处理：预算仍使用五模块批次报告；合同保留八项和visualBase原页预览；采购保留去除顶部三个金额槽位、问题优先及原页高亮。没有用远端旧文件覆盖本地后续成果。融合前副本、差异及清单位于 `.runtime/github-ui-sync-20261109/`，`fusion-only.diff` 仅包含本轮变化。

本轮没有创建Git提交，工作目录HEAD仍为41c60c5e。发布清单分别记录baseCommit、integratedRemoteCommit和实际源码哈希，不能将远端提交号冒充整个工作树身份。

## 本地验证

- Windows / JDK17.0.18，在backend运行 `./mvnw.cmd clean verify`：3642项，0失败、0错误、24跳过，501个测试套件；BUILD SUCCESS。首次使用JDK21被Java版本约束拦截后切换已安装JDK17重跑通过。汇总 `backend-tests.json`。
- frontend目录执行 `node --experimental-transform-types --test`，参数为src下全部 `*.test.ts`：865项通过。日志 `frontend-tests.log`。
- frontend执行 `npm run build:customer`：构建成功，CHROME109_COLORS_READY=220。入口脚本 `index-wQoG7syQ.js`，保留原字体和Chrome109颜色兼容处理。
- 设置 `AUDIT_BROWSER_EXECUTABLE` 为现有Chrome109后执行 `node --test frontend/tests/processing-status/processing-status.browser.test.mjs frontend/tests/evidence-viewer/evidence.browser.test.mjs`：16项全部通过。覆盖等待到终态、操作恢复、未知状态停止转圈、原页/文字切换、定位和弹窗边界。日志 `browser-tests.log`。
- `git diff --check` 通过。

## 26发布与真实浏览器

在现有release `/opt/audit-agent/releases/evidence-original-20261109-r2` 内做静态更新，UI修订为 `github-ui-20261109-r1`。先写入资产，再原子替换index；保留原资产供已打开页面使用，保存旧入口用于恢复。没有重启服务或更换backend/AI，没有执行数据库迁移。

- 新入口SHA256：`ad893a25fd920ce55a9526c82eba24261ece5cfa1dfd3b170a435447c50790ad`。
- 后端JAR保持：`d840b3e23246670ea1c7c3e6259da79bc99e4cc06e89d5c988671b71d2abf2d8`。
- 默认80端口7份本次静态文件全部HTTP200且哈希一致；后端和AI进程PID、AI源码、配置哈希保持。
- `GET :8080/api/v1/health`：`{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}`。
- `GET :8001/health`：`{"status":"UP","service":"audit-ai"}`。

Chrome109实际访问 `http://192.168.0.26/`，登录后打开三模块既有真实处理结果：合同八项仍展示；三模块原PDF像素与坐标高亮、解析文字、关闭重开，以及1440×1000/760×560视口正文不越过标题均通过。没有页面脚本错误。

另外在同一真实生产页面对只读GET响应临时模拟RUNNING/PROCESSING，三模块均只展示一个无边框/透明背景/无阴影状态区，未显示未完成的结果模块、查看原文或上传控件。模拟仅发生在测试浏览器，不修改服务任务和持久化结果。终态恢复另由6项组件状态测试覆盖。本轮没有重新调用模型或OCR，不宣称重新验证了材料语义准确率。

发布、浏览器、源码清单证据：`.runtime/github-ui-sync-20261109/{deployment.json,browser-verification.json,manifest.json}`。本轮没有Git推送或甲方离线包；甲方ARM64现场仍NOT VERIFIED。

## 后续发布核对

用户要求融合GitHub时，先刷新远端分支，列明上次已融合提交与新增提交，再与当前未提交修改三方融合。发布清单同时保留远端融合截止提交、实际源码清单和入口SHA；部署后从26默认80入口获取HTML及全部引用资产核对，并用浏览器检查对应改动。不能以“已拉取”“本地构建成功”代替26已更新，也不能只凭backend的baseCommit判断前端身份。
