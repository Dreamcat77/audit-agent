# 测试结果

本轮环境：Windows、JDK 17.0.18、Node 24.14.0；真实服务验收使用 26 独立 QA。

- Java：`backend/mvnw.cmd clean verify`，3346 tests / 0 failures / 0 errors / 24 skipped，BUILD SUCCESS。日志：`docs/qa-26/evidence/unified-java-verify-r3.log`。其中架构保护测试 25/25 通过，未修改冻结指纹。
- 定向 Java：公共解析、预算、采购、合同 76 tests / 0 failures / 0 errors / 4 skipped。日志：`.runtime/unified-targeted-r1.log`。
- 前端：`node --experimental-strip-types --test <rg列出的src/*.test.ts>`，834 passed / 0 failed。日志：`.runtime/unified-frontend-tests-r4.log`。
- 上传进度传输：4/4 通过，覆盖实际字节进度、CSRF/Session、409机器分类数据、401/403/503、网络未知/超时/取消不重放POST。
- 生产构建：`frontend/npm run build` 成功，2459 modules；保留既有大于500KB的bundle告警。
- Python：`services/audit-ai` 使用 `.test-runtime/contract-auto-python/Scripts/python.exe -m pytest -q --basetemp=E:/audit-agent-new/.runtime/unified-pytests`，2029 passed / 14 skipped / 43 subtests passed。
- `git diff --check` 成功。已检查 tracked diff 未含用户提供的两组真实密码。

修复过程：Java r1 因历史生成目录扫描耗时，确认线程后终止；r2 的 3346 项测试唯一失败是共享client.ts冻结指纹。将上传传输隔离到clientUpload.ts并逐字节恢复共享客户端后，r3全量通过。没有删除业务测试、放宽证据断言或更新冻结指纹。

真实 DM8/模型/浏览器和多格式验收正在执行；下方仅在观察到执行结果后补充，不以历史r5结果冒充当前版本验证。
