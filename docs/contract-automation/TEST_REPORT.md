# 合同隔离验收记录

截至2026-10-27，第一阶段合同独立切片隔离验收已通过下列测试，进入26三模块测试阶段。全仓及26整体尚未通过，不能将此结果描述为正式交付完成。

## 已执行

- JDK17合同/项目/Review广泛回归：1734 tests，0 failures，0 errors，8 skipped；日志 `evidence/p5-contract-final-regression.log`。
- 新增跨文件比较与报告投影后定向回归：84 tests，0 failures，0 errors；日志 `evidence/p5-comparison-report-java.log`。
- Python合同Router/Schema/Prompt/单次超时：14 passed；日志 `evidence/p5-cross-final-python.log`。存在一个已有Starlette弃用警告。
- 最新前端生产构建通过，保留既有chunk-size/import警告；日志 `evidence/p5-final-production-build.log`。本项是构建证据，不等于浏览器验收。
- 隔离真实DM8案件平台：8个竞争者仅1个取得claim，拒绝过期/迟到写入，新仓储可重开快照，UNKNOWN不重调，回滚保存事实；日志 `evidence/p5-dm8-platform-probe.log`。原业务库未修改。
- 已有真实模型Golden包含正常采购/服务/租赁、缺字段、金额大小写、付款比例、中标金额/供应商、混合合同、注入指令、12行同名表格、补充协议及倒置扫描。逐例结论以对应 `e2e-*-verification.json` 的failures为准。
- 倒置扫描：真实OCR后真实模型得到10事实、8条款、0风险，23处来源一致，案件COMPLETED。
- 最新明确有效变更：14事实、12条款、2项有双方证据的比较，0风险。部分模型引用被拒收，案件如实PARTIAL，不宣称全篇审查通过。

## 最终隔离验收

- 15组合成Golden的逐场景断言全部通过；独立新进程重新读取冻结原文，488处引用的文件SHA、解析身份、偏移及原文内容一致。日志 `p5-fresh-all-golden-verification.log`。这证明这组样本，不等于未知甲方材料准确率100%。
- 接收成功但内部XML损坏的附件与正常合同混合测试通过：PARTIAL、10事实、8条款；有效合同结果未丢弃。
- HTTP503、模型结果未知、非法JSON、空结果四类故障及重启不变测试通过；详见 `p5-fault-injection-verification.json`。明确faultInjection=true、realModel=false。
- 实际Chrome109.0.5412.0、最新生产包、真实模型和隔离DM8：浏览器上传无编号无金额合同，零业务字段输入，COMPLETED，原文可读、刷新报告一致、历史可重开、pageErrors=[]。证据 `browser109-notice-fixed-verification.json` 及对应截图。顺带修复了完成后旧接收通知仍显示“正在分析”的页面问题。
- 最新补充协议冲突场景得到两项具体风险，跨文件解释使用“付款方”而非猜测乙方付款；独立负向责任断言及双方来源复核通过，38处证据一致。单文件和跨文件发现仍分开保留，不声称人工法律审核结论。
- 最新Python合同14/14、前端通知状态3/3通过；最新生产包构建通过（既有chunk/import警告）。

## 全仓状态

最近完整Java基线为3277 tests、8 failures、5 errors、24 skipped，13项均属既有采购测试集合。全仓尚未BUILD SUCCESS；这些失败不能隐藏或仅因历史原因忽略，将在第二阶段逐项核对并修复。

此前前端830/830与AI/OCR 2026 passed、14 skipped、43 subtests passed为各自记录时的代码结果，不能用它们代替最终三模块版本回归。

合成DOCX样本尚未以LibreOffice视觉渲染，本机未安装该工具；未自行安装系统软件。浏览器截图验证的是系统的实际结果页面。
