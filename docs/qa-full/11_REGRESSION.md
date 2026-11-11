# 11 Regression

| 命令 | 环境 | 本轮结果 |
|---|---|---|
| `node --experimental-strip-types --test "src/**/*.test.ts"` | Node 24.14.0，frontend | 877 pass，0 fail，0 skipped；PASS |
| `npm.cmd run build:customer` | Vite 6.4.4，Chrome 109 产物预处理 | build 成功；`CHROME109_COLORS_READY 219`；有 chunk 大小及动态/静态导入警告 |
| `backend/.\mvnw.cmd clean verify` | Temurin JDK 17.0.18 | 3654 tests，0 failures/errors，24 skipped；BUILD SUCCESS，7m03s |
| `.runtime/qa-full-python311/Scripts/python.exe -m pytest services/audit-ai/tests -q` | Python 3.11.9；requirements-test 隔离环境，无 Paddle 核心依赖 | 2284 passed，14 skipped，43 subtests passed，1 Starlette deprecation warning，74.70s |
| AST parse `services/audit-ai/src` + `tests` | Python 3.11.9 | 182 个 Python 文件语法解析通过；不替代行为测试 |
| OCR synthetic rotated PDF POST `/ai/v1/ocr/extract` | 26 QA OCR loopback `127.0.0.1:19153`, PaddleOCR 3.7.0 | HTTP 200；1 page, 541 chars, confidence 0.9982, OCR 20.6s / total 23.8s；PASS for service execution only |
| `acceptance.py contract rotated-scan --revision r6` | 26 QA API `127.0.0.1:18082`, real DM8, synthetic scan | Original text READY; report PARTIAL; 8 categories, 6 checks, 1 finding; model FILE step `MODEL_HTTP_502`; failures `MISSING_CLAUSE_COVERAGE`, `UNEXPECTED_RISK_ON_NORMAL_CONTRACT` |
| `acceptance.py budget over uppercase missing-item excel partial --revision r6` | 26 QA API, real DM8, synthetic files | XLSX passed (PARSED, 1 line); DOCX/TXT scenarios UNKNOWN with 0 lines; partial valid+damaged batch UNKNOWN/FAILED with 0 lines; semantic cases blocked by model HTTP 502 |
| `acceptance.py procurement normal conflict --revision r6` | 26 QA API, real DM8, synthetic files | Both cases DATA_INSUFFICIENT; file states UNKNOWN; AGENT_PRECHECK/TENDER_COMPLIANCE UNKNOWN; no offers/evidence; blocked by model HTTP 502 |

本轮未有产品代码改动，因此不存在修改后的产品回归结论。远端测试只新增合成 QA 记录，报告与验证产物保存在 `/home/admin/audit-agent-backups/qa26-run-20261111-r9/results/`。前端 customer build 在 2.08 MB 主 JS chunk 上有 Vite 警告，构建通过但可能影响首次加载性能，需另行评估，不作为功能失败。QA web v22/backend v24 不匹配，远程 API 结果不等于该 QA 前端版本的浏览器端到端验证。
