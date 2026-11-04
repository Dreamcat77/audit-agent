# 预算、采购发布与回归（2026-10-19）

范围：用户负责的预算、采购及共用材料读取、模型调用、持久化、上传与报告边界。基于融合提交 `e17a1d3eca75c402c371f2adb58d0d59676f4b84`，发布分支 `fix/budget-procurement-ready-20261019`。不提交运行包、环境文件、密钥或业务材料。

## 部署

26 已切换到 `/opt/audit-agent/releases/material-timeout-20261019.8Ert0b`。
旧版本保留，回退记录 `/opt/audit-agent/update-backups/material-timeout-20261019.Wtk26r`。

- 运行 JAR SHA-256：`0d2de22946174ecd3ae45df5118cdc7a1b058cbb93b4a829c3ae21dd3b73c383`，与本地通过构建产物相同。
- 部署归档 SHA-256：`1bd9a0a98b23abaf9bb4df6054ba93275a54e9dae49ba9dc237eec5a217baf68`，上传后相同，manifest 通过。
- V052–V056 只读结构检查匹配；V057 仅新增 nullable TIMESTAMP `DEADLINE_AT`，应用前后均检查。没有修表或改写既有任务。
- 旧版任务排空后才停服务。新版后端、AI HTTP200；匿名预算/采购接口401；首页及三项资源与磁盘字节相同。
- 配置哈希 UNCHANGED：模型地址、密钥、模型名称、Nginx、systemd 和环境文件不变；Python/OCR 虚拟环境和模型文件未替换。

健康响应：

```json
{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}
{"status":"UP","service":"audit-ai"}
```

单次材料模型读取等待 180 秒、共享并发 1、模型提取阶段每份材料 900 秒，超时收口/清理规则与限制见 [生命周期证据](material-timeout-lifecycle-20261019.md)。真实服务日志已经观察到材料调用 `read_timeout_ms=180000`；不把其他路由的 60000 改成全局 180000。

## 本地验收

- `cd backend; .\mvnw.cmd clean verify`：2418 tests，0 failures，0 errors，2 skipped，BUILD SUCCESS。
- Python 全套：551 passed，1 skipped（Windows 符号链接权限），1 warning。
- 前端 748 passed；`npm run build` 及 Chrome109 兼容资源构建通过。
- DM8 隔离表迁移幂等、错误字段类型拒绝、截止时间不可重置、超时清除候选、迟到 ACK 与错误 token 拒绝均 PASS，隔离表已删除。
- 新增发布探针时间戳回归：`python tools/test-material-timestamp-precision.py`，2 tests PASS。真实 DM8 把 Java 纳秒四舍五入到微秒，两个本轮合成报告观察到 +374/+380 纳秒；验收只允许此精度归一，其他时间及业务字段变化仍失败。产品数据未为通过测试而修改。

## 新版真实链路

全部使用合成材料，临时测试身份经匿名管道注入、结束后禁用，保留结果和审计。未读取甲方业务材料。

1. `BudgetBackgroundRunner review_probe.py procurement`：正常招标没有虚构风险；风险招标检出指定品牌、地域、资质、排他及交付期限冲突，六项提示有证据；三份报价和围串标计算通过；两个完整报告六章节导出及新登录重开通过。
2. `BudgetBackgroundRunner test-deployed-reliability26-v2.py`：12 份 DOCX/XLSX/TXT 预算混合上传和 2598 字叙述材料分段提取通过。大写申报 120000、材料批复 100000 和超支判断一致；13 份预算结果、2 份采购报告在新会话及后端重启后指纹不变。
3. `MaterialDeploymentAcceptanceCheck`：预算2单元2调用；两份采购分别2单元2调用，均 COMPLETE，累计预算持久化。报告完整快照和预审快照相符，PASS。
4. `BudgetBackgroundRunner final-office-complete.py`：真实扫描 OCR→模型→规则→报告，指定品牌、地域、排他三类原文风险检出，DOCX 导出和新登录重开 PASS。
5. 真实 Chrome `109.0.5412.0` 连新版：扫描 PNG 上传→自动结果页→大写金额123456.78、材料批复100000、差额23456.78红色提示→XLSX导出→刷新预算首页→按材料名查历史重开；采购报告复制按钮切换及DOCX导出 PASS，`pageErrors=[]`。已查看结果截图，文字与差额可读。
6. `BudgetBackgroundRunner test-office-formats26.py all`：预算、采购各 16 份样本，各 14 份有效材料全部解析通过，预设损坏文件和超限材料正确失败或拒绝，无 UNKNOWN、无意外失败，`REAL_OFFICE_UPLOAD_MODEL_DM8=PASS`。覆盖 DOC/DOCX、XLS/XLSX、PDF、PPT/PPTX、TXT/UTF-16、CSV、RTF、表格 DOCX、约 26 MiB DOCX 及长文本；校验字段、金额与行覆盖。测试身份结束后禁用，保留审计。

部署脚本：`tools/deploy-material-timeout.sh`。该脚本需要带 manifest、旧版本身份及 payload 的受控发布目录；不是只复制脚本到任意机器即可执行。受控发布目录及验收日志保存在代表测试机，未上传 Git。

## 验收边界

本轮实际材料的结果有依据，不代表任意模型输出都必然正确。未知调用仍禁止盲目重发，既有 UNKNOWN 未批量清除。15 分钟只覆盖模型提取阶段，排队与 OCR 另计；上游读取超时不代表上游模型已取消。多页大批扫描的吞吐/HTTP 等待限制见前序扫描验收，甲方 ARM64、本地模型及网关白名单仍须现场验证，NOT VERIFIED。
