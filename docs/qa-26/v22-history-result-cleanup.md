# v22 采购历史与三模块结果页修复

## 范围与原因

采购新历史页 `/api/v1/procurement/cases/history` 的空条件由 `historyParams()` 生成含 undefined 的属性。共享客户端通过 URLSearchParams 序列化后，实际发送 `keyword=undefined&from=undefined&to=undefined`。后端 Instant 绑定拒绝，HTTP 400；并非已经证明数据库历史记录损坏。

26 默认入口修复前真实请求：无参数200（5条）、无效undefined参数400、名称筛选200、日期区间200、2条分页和游标续页均200。见 `evidence/v22/history-before.json`。不删除业务历史，不隐藏真正的查询错误，不改变后端日期校验。

## 实现

- 移除采购“查看早期采购记录”入口；新历史仍可筛选、翻页、打开原批次。
- `automaticHistoryParams` 仅发送有值条件，验证完整有效日期，名称最多160字。历史服务失败不再阻断原上传操作恢复，原操作键保持不变。
- 三模块采用共享 ResultSection / ResultItem：无问题项折叠，问题项默认展开；用户可以自行开合。合同八大项容器始终保留，模块按实际风险状态开合；正式八项、五步和确认功能保留。
- 明确已核对正常的项目用浅绿色；信息不足/未知/仅整理参考内容保持中性。超预算、超限价、明细不一致、黑名单命中或近似及规则问题保持警示。
- 采购系统生成的身份核对改用项目名称、项目编号、采购单位；移除“字段”用语。旧报告只读展示、问题复制和HTML导出同样转换，不篡改持久化原文、业务名称、引用或确认ID。
- 合同正式审核诊断明细不再向用户展示内部属性路径、错误枚举、工作单元及指纹；仍保留金额复核提示和依据。

主要文件：`frontend/src/components/results/ResultLayout.tsx`、`frontend/src/styles/result-layout.css`、`AutomaticCasePanel.tsx`、`automaticCaseState.ts`、`BudgetFileResults.tsx`、`ContractAnalysisCategories.tsx`、`ContractAnalysisWorkspace.tsx`、`EightCategoryReviewPanel.tsx`、`CaseReviewEngine.java`、`CaseReviewText.java`、`CaseReportHtml.java`、`ContractAnalysisReport.java`。

## 验证

- Windows JDK17：`backend/.\mvnw.cmd clean verify`，3427项，0失败、0错误、24跳过。日志 `.runtime/v22-java-final-verify.log`。
- Node：`node --experimental-strip-types --experimental-transform-types --test <全部src/**/*.test.ts>`，846项全部通过。覆盖空/部分/非法日期、未知不能标绿、正常折叠/问题展开、八项保留、原文保真及旧报告自然语言导出。日志 `.runtime/v22-frontend-final-tests.log`。
- `npm run build:customer` 通过，包含Chrome109颜色和本地字体兼容处理；新资源目录 `assets/update-v22`。
- 26 隔离18082真实DM8持久化结果：Chrome109.0.5412.0，8批（采购3、预算2、合同3），名称/日期/清空历史筛选12次请求均200且无undefined参数；报告展开/折叠、问题复制、采购HTML和预算Excel导出通过。三模块1440/1024/390宽度无横向溢出，0脚本错误。证据 `evidence/v22/candidate/browser-results.json` 与截图。
- 26 默认80入口已切换至 `v22.material-20261029-r1`，后端/AI健康200，9批（预算4、采购3、合同2）历史重开及筛选12次均200；三模块1440/1024/390宽度、展开/折叠和报告导出通过，0脚本错误。见 `evidence/v22/default-deployment.json` 和 `default/browser-results.json`。部署无DDL，保留配置、Nginx、数据和离线模型。实际运行JAR SHA为 `3f728e50518c35b8837707e5b9a2cea3337d6c6321210aebc9ffeeb62e592a4b`，index SHA为 `be33d0929cfca0a212a8283ef20f3ab4e0e0afedf2e85f869e3a5f391ff51a2d`，与候选清单相同。
- 暂存切换前曾因26辅助脚本缺少 `preserve_ocr_models.py` 停止；未切换current或停止旧服务。补齐工具，保留未完成暂存目录后重试。此为26部署辅助问题，甲方update.sh仍使用v15已验字节，不引入新辅助依赖或前置要求。

## 外部验证边界

本轮26到模型端口TCP仍不可达；验收使用既有真实持久化结果，不重算历史、不宣称新的模型推理质量通过。AI源码与v20一致，沿用当时2016通过/15跳过证据，未重新执行Python整组测试。甲方ARM64实际部署与真实材料仍 NOT VERIFIED。无Git提交/推送。

## 交付

新名字 `Update_v22.tar.gz`；在新目录解压并进入含update.sh目录，仅执行 `sudo bash update.sh`。成功标志 `UPDATE=PASS`，浏览器Ctrl+F5。安装脚本、schema、SQL、Java17结构检查器与v15逐字节一致，无新DDL或恢复JSON要求；最终tar再解包核查清单、SHA、LF、Java17、静态资产和OCR权重。最终证据见 `evidence/v22/package-verification.json`。
