# 个人上传角色验收（2026-11-04）

这是 r1 的历史验收记录。用户后续要求直接在数据库维护账号角色关系；当前代码已移除本次新增的管理员授权页面及快捷接口，三个角色和文件结果隔离继续保留。最终版本以 r2 验收记录为准。

版本 `personal-roles-20261104-r1` 已部署到 26 默认入口，后端、AI 和 Nginx 均正常。后端 JAR SHA-256：`a6b2e969411ba5797b32aa85219c13e0c6f1a63e215de0ad5463cc6ad05943a9`；前端入口 SHA-256：`b0e7885d4b3c5ccd378e6818d551d0413073e984dbe4b29cc7ac4e534c8769b3`。配置哈希保持，未执行 DDL。

采购、预算、合同三角色都能使用三个工具，材料归属按账号 ID 区分。原有不带个人角色的管理员行为保留；个人角色与旧角色混合时仍执行个人材料限制。管理员“角色授权”针对已有账号保存授权，复用原有角色关系和操作审计。

验证命令及结果：

- JDK 17，`backend/mvnw.cmd clean verify`：3484 tests，0 failures，0 errors，24 skipped；日志 `.runtime/personal-roles-clean-verify.log`。
- 前端 `node --experimental-strip-types --test` 执行全部 `.test.ts`：849 passed；`npm run build:customer` 成功，Chrome 109 颜色兼容处理成功。
- 真实 DM8 执行 `V058__personal_upload_roles.sql` 两次：三条角色参考数据均启用、无重复。只增加参考数据，不改历史用户授权、材料或表结构。
- 26、Chrome 109.0.5412.0：四个独立临时账号（三种角色，其中两个同为采购），各上传预算、采购、合同材料。预算 4/4 PARSED；合同 4/4 COMPLETED，均读取 344 字、5 条款、生成八项。采购使用同一合成合同材料，4/4 DATA_INSUFFICIENT 且均生成可读报告，不把缺少报价误称为报价审查通过。
- 每对账号互相请求七类他人结果、报告、项目和原件链接：84 次 HTTP 404；跨账号预算确认写操作 12 次 HTTP 404。同角色账号同样隔离，各自历史只返回本账号材料。
- 刷新页面后重新打开历史结果、合同八项、本人原件与采购 HTML 报告成功；四个账号页面无 JS 错误或横向溢出。
- Chrome 109 管理员角色授权保存成功、重复授权不重复写关系；该快捷接口拒绝授予 SYSTEM_ADMIN，个人账号调用返回 403。

测试首次用 API 上传后未刷新已打开的历史页面，浏览器脚本等待旧页面列表超时；修正测试脚本并重新登录读取原持久化任务完成复验，没有再次上传或重跑模型。测试使用完全合成材料；临时账号已停用，材料及审计保留。

证据：`.runtime/personal-roles-20261104/verification.json`、`deployment.json`、`role-0..3-chrome109.png`、`admin-role-assignment-chrome109.png`。脚本为 `.runtime/personal-roles-e2e-control.py` / `personal-roles-e2e.mjs`，凭据仅交互输入和内存传递。甲方现场本轮 NOT VERIFIED；本轮未制作甲方更新包、未提交或推送 Git。
