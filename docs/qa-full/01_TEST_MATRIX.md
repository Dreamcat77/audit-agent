# 01 Test Matrix

| 区域 | 覆盖项目 | 本轮执行 | 状态 | 后续要求 |
|---|---|---|---|---|
| Java | 全模块 clean verify | `backend/.\mvnw.cmd clean verify`，Temurin JDK 17.0.18 | 3654 tests；0 failures/errors；24 skipped；BUILD SUCCESS | 本地 mock/fake 与集成测试，不代表客户 DM8 |
| Python | AI/OCR pytest | 隔离 Python 3.11.9 + requirements-test | 2284 passed，14 skipped，43 subtests passed；1 Starlette deprecation warning | Paddle 核心包未安装，pytest 以 fake/schema 测试为主；POSIX 对照 checkpoint 尚未做 |
| Python | AST syntax | Python 3.11.9，对 `services/audit-ai/src` 与 `tests` | 182 files parsed | 语法通过不等于模型/OCR runtime 通过 |
| 前端 | node test | 877 pass / 0 fail / 0 skip | PASS | 做 module browser E2E 及权限验证 |
| 前端 | Vite Chrome 109 customer build | 2484 modules；`CHROME109_COLORS_READY 219` | PASS（有 chunk warnings） | 三模块浏览器流程与证据弹窗 |
| 文件/OCR | 20 类文件矩阵 | 未运行 | NOT TESTED | 仅用授权合成/仿真样本建立逐页 oracle |
| AI 质量 | 预算/采购/合同分模块 truth set | 未运行 | 缺专家标注，不能算准确率 | 人工复核标注集 |
| 证据 | 原文件逐页/区域核对 | 未运行 | NOT TESTED | PDF 原页和 Office 来源定位专项 |
| 异步可靠性 | 重启、lease、unknown、幂等 | 未运行 | PARTIAL SOURCE EVIDENCE | 隔离环境故障注入 |
| 26 环境 | 主机/DB/Redis/服务版本 | 仅 GET health/index | PARTIAL | 需要授权只读 SSH/诊断输出 |
| 压测 | 历史接口/上传/OCR/模型/写入 | 未运行 | NOT TESTED | 确认隔离和阈值后分档压测 |

本轮没有因代码构建或前端测试通过而判定业务分析质量通过。
