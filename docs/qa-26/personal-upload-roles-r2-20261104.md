# 角色数据库维护版验收（2026-11-04）

按用户要求移除本轮新增的管理员“角色授权”导航、页面及 `/api/v1/system/upload-roles` 接口。采购、预算、合同三个角色，以及按账号限制文件、结果和报告的逻辑保留。账号角色关系直接维护现有 DM8 `SYS_USER_ROLE`；操作说明见 `docs/personal-upload-roles.md`。未增加数据库表或迁移。

26 默认入口部署为 `personal-roles-20261104-r2`。JAR SHA-256：`d769c9c8be2132a28c7bbc8ec100c1df513e94873de2bc2da9603d445b803e49`；前端入口 SHA-256：`b2b4c3b06cced2ee3d7085be7adfea4494fdddd6273d5e33d9e57f36a655ef4c`。后端、AI、Nginx 正常；静态资源 HTTP 哈希匹配，配置和历史数据保留。

JDK17 `backend/mvnw.cmd clean verify`：3483 tests，0失败，0错误，24跳过；前端全部 `.test.ts` 849通过，`npm run build:customer` 成功。最终 JAR 中确认授权控制器已移除、归属检查拦截器保留，`git diff --check` 通过。

26 Chrome109 复用上一轮四个测试账号和已保存的12份材料，不重新上传、不重跑模型。刷新历史、本人合同八项、原件和采购HTML导出通过；84次跨账号读取及12次跨账号预算确认均404，同角色隔离保持。管理员页面不再显示角色授权，旧快捷授权接口返回404。临时测试账号再次停用。证据在 `.runtime/personal-roles-r2-20261104/verification.json`、`deployment.json` 和截图；本轮未制作甲方更新包、未提交Git，甲方现场 NOT VERIFIED。
