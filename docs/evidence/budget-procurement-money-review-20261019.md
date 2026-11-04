# 预算、采购金额及模型链路检查

工作区：当前 feature worktree；原有未提交修改全部保留，未提交、推送或切换正式运行版本。本轮不修改业务数据库、AI 地址、Key、模型名称、Nginx 或前端布局。远端只创建独立合成测试目录和 loopback 临时服务；扫描识别调用测试服务器已有 OCR HTTP 服务。

## 已修复的行为

- `RmbAmountParser` 使用 `BigDecimal` 统一处理阿拉伯数字、人民币大写、角分、部分繁体和明确金额单位。拒绝歧义写法、大小写金额矛盾、超精度和溢出，不舍入、不让模型计算金额。预算、采购字段归一化及报价提取复用此解析器。
- 采购报价原来在“元”处提前截断，可能将含角分的大写金额变成整数。现在完整读取角分及合法“整/正”尾缀；错误角分、零报价与有效报价混用保持拒绝。增加繁体角分用例。
- 预算旧格式校验器保留负数、超过两位小数等明确诊断；合法大写金额通过格式校验，非法值仍不能进入金额比较。
- Excel 预算读取识别工作表单位与批复参考额度等列名。存在未知列时保留表头和原行交模型处理，不能因识别了部分列就忽略剩余金额列。
- 对申报/批复角色增加原文标签验证，拒绝模型把两种金额对调。相同供应商的重复证据不重复累计，不同已识别供应商的等额明细不再折叠成一笔支出。版本冲突不自动相加。
- 同一供应商在同一材料的不同原始行出现多条申报时，没有可靠明细身份就不把等额明细折叠成一笔，也不猜测合计；返回不可确认或冲突。AI 行级证据和确定性列级证据会归到同一原始行，避免重复视为支出。
- 多份采购材料自动填充时保留金额冲突，避免先到先得。长需求保留明确标记的短摘录，完整证据继续存于既有证据结构。
- 对同一预算科目复用比较结果和证据，降低重复归集开销。远端合成 benchmark：1000 行、5000 个证据引用，436ms；不代表完整 HTTP/数据库响应时间。
- F10 使用独立分段预算、完整 JSON 结构和剩余调用时间；跨页定量条款增加原文对照及专门提示。必须同时比较的条款遇到模型限制时不允许拆成彼此无关的“无风险”结果，而是明确失败。显式重试可恢复此前模型超时/不可用的失败。

## 本地验证

Windows，既有 JDK 17.0.18、隔离 Python 3.11；不安装系统软件。

```powershell
$env:JAVA_HOME='C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot'
cd backend
.\mvnw.cmd clean verify
```

最终后端：2350 tests，0 failures，0 errors，2 skipped，BUILD SUCCESS。预算上传材料比较专项 38 项通过，包含不同明细等额和同一行重复证据两类反向用例。日志：`.runtime/review-20261018/backend-clean-verify-final.log`。

```powershell
.runtime/material-python311/Scripts/python.exe -m pytest services/audit-ai/tests -q --basetemp=E:/audit-agent-new/.runtime/pytest-short
```

最终 Python：524 passed，1 skipped；跳过 Windows 缺少符号链接权限的用例。旧长路径临时目录曾造成缓存测试失败，短路径下完整通过；未通过放宽缓存实现规避测试。日志：`.runtime/review-20261018/python-final.log`。

```powershell
$env:PYTHONPATH='services/audit-ai/src'
.runtime/material-python311/Scripts/python.exe tools/test-material-model-limits-local.py
```

真实本机 socket HTTP 限制模拟：24 份预算/采购文本，219～96019 字符；逐份核对首中尾字段，全部通过。输出 `LOCAL_SOCKET_HTTP_24_MATERIALS=PASS`；仿真 provider 拒绝 1210 次、接受 1638 次。日志：`.runtime/review-20261018/local-limits-final.log`。此检查不是外部商业模型测试。

前端最终回归：745 项通过、0 失败，包含采购表单金额冲突的 5 项用例；构建成功。日志：`.runtime/review-20261018/frontend-final.log`。此次金额收尾未再改变前端源码。

```powershell
node tools/build-procurement-browser.mjs frontend/dist .runtime/review-20261018/frontend-chrome109
node tools/test-budget-material-background.mjs E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe .runtime/review-20261018/frontend-chrome109 review-money-final-chrome109
```

Chrome 109.0.5412.0：上传/文件夹拖动、超过20MB、单文件失败隔离、丢失响应恢复、离开/刷新、结果跳转、历史检索分页、复制与HTTP回退等检查通过，浏览器错误数组为空。对最新 JS 构建应用既有颜色兼容补丁后重新验证；CSS 布局与原始前缀保留。结果：`.runtime/budget-background-browser/review-money-final-chrome109.json`。API 为合成测试 seam，不能作为真实 DM8 E2E。

## 远端真实模型与 OCR

仅使用合成材料，不上传甲方材料，不记录密钥、配置正文或真实材料内容。调用测试服务器现有配置的 Qwen 27B 模型；模型 HTTP 文本测试与真实 Office/OCR 适配器测试分别记录，不能互相替代。

已观察到的真实结果：

- 最新源代码以两路并发处理 16 份合成文本：预算和采购短材料 4 份、约 9800 字材料 12 份，全部 HTTP 200，预期金额原文均保留，失败 0。
- 预算语义分析 12 条明细：HTTP 200，12 个 itemId 全部返回，包含有效语义字段。
- 12 页招标文本：最初只识别品牌限制，漏掉首尾交付期限差异；加入同主题跨源对照后，HTTP 200，识别两类风险，首尾差异检查通过。
- Java Office 读取 → AI HTTP → 证据验证/金额归一化：TXT、DOCX 大写金额、Excel 万元单位/批复列、采购大小写金额不一致拒绝、预算自然语言申请/核准角色，以及普通 DOC/PDF 合成材料均有通过记录。
- 中文大写扫描图：预算、采购两条 OCR → 模型 → Java 金额检查均通过，正确读取 123456.78 元，预算批复 100000 元。两项分别约 62/66 秒。第一次图片生成误用 Windows 默认源码编码造成乱码，已改 UTF-8 编译并人工检查图片后重测；该次失败不归因于产品 OCR。

新增重复执行工具：`tools/test-commercial-material-money.py`、`tools/CommercialMoneyWorkflowProbe.java`。合成扫描图及调用日志位于隔离测试目录。

测试结束已停止本次 loopback 服务，未删除既有远端目录。正式后端、AI 服务均为 active，真实 HTTP 健康响应分别为 `{"code":200,"message":"success","data":{"status":"UP","service":"audit-backend"}}` 和 `{"status":"UP","service":"audit-ai"}`。

## 验证边界

本轮没有部署正式服务，没有执行数据库迁移；没有完成新版本“真实浏览器上传 → DM8 持久化 → 刷新重开”的完整远端 E2E。甲方本地模型、网关白名单、具体限额与实际扫描质量：NOT VERIFIED。清晰合成扫描图通过不能承诺任意扫描件无误；缺失、冲突或不可确认的金额仍需明确提示人工核对。
