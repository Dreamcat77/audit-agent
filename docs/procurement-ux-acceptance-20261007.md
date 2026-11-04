# 采购上传与统一分析交互验收（2026-10-07）

功能提交：`210a53c`。范围为采购页面及其材料编排接口，不涉及其他业务页面、数据库迁移或风险阈值调整。

## 行为

- 需求材料解析完成即自动填表；处理中自动查询原操作结果，保留原幂等键。移除“使用提取字段”按钮，缩小上传操作按钮。
- 新页面通过 `POST /review-materials/uploads` 保存材料，通过 `GET /review-materials/current` 重开清单。上传不执行合规风险扫描、比价或围串标评分。
- 用户明确点击“开始分析”后，`POST /review-materials/analyze` 使用清单中的 source IDs 统一处理适用检查并自动保存报告。成功后直接进入结果确认。
- 同一材料范围重试重开同一个报告；追加材料使旧报告不可继续使用，过时清单被拒绝。上传、确认归属与分析共享容量限制及项目互斥。
- 结果页保留分析明细、相似度及依据入口。严重风险确认及长期审计链保留。采购界面的 Fxx 展示前缀移除，技术标识兼容。

## 自动化验证

Windows、JDK 17：

```powershell
cd backend
.\mvnw.cmd clean verify
```

观察结果：2144 项测试，0 failures、0 errors、2 skipped，BUILD SUCCESS。覆盖分批上传、只保存不分析、显式分析自动持久化报告、重复分析复用报告、追加材料拒绝旧清单、CSRF/角色限制、同项目并发拒绝及释放、已有投标人确认保留。

```powershell
cd frontend
node --test --experimental-strip-types "src/**/*.test.ts"
npm.cmd run build
```

观察结果：698/698 通过，Vite 构建成功。原有大包体积警告保留。`git diff --check` 及本轮新增差异敏感信息检查通过。

## 浏览器组件验证

通过仅在本地、忽略目录内运行的隔离接口夹具，加载真实 React 组件并操作上传控件：

- PARSED 响应立即填入项目、部门、金额、科目等字段。
- PROCESSING 经原操作查询转为 PARSED，无需刷新，字段自动更新。
- 连续上传两批后显示三份文件；请求清单没有分析或报告生成调用。
- 点击一次分析后收到报告，按钮显示“已分析，报告已保存”。

上述是组件交互验证，不能替代真实 AI 字段提取验收。部署环境的已登录完整浏览器流程本轮 NOT VERIFIED：浏览器当前无有效登录会话。

## 部署及真实 DM8

目标测试环境已切换到 `/opt/audit-agent/releases/210a53c-20261007-procurement-ux`，前一版本 `d0b4063-20261007-procurement-batch-analysis` 保留可回滚。发布包 SHA256：`18ecb89b7d782e626fe6a61ed34590802633aaffa898ce48f093897bfa475714`。

站点与 `/api/v1/health` 实际 HTTP 200。健康响应：

```json
{"code":200,"message":"success","data":{"status":"UP","service":"audit-backend"}}
```

后端、AI 服务及 Nginx 均 active。实际站点 JS 含新上传、统一分析路径、自动填表和文件清单文案，不含“使用提取字段”按钮文案。

服务器受控验收进程使用已有 test_admin 身份，通过正式应用服务连接真实 DM8；不创建登录会话、不提交审批、不确认严重风险。目标仅为已有模拟项目“综合办公设备更新采购项目”，预算 180000 元。操作前保留范围内结果快照。

服务器命令为 `bash acceptance.sh run <test-project-id> <material-directory>`，观察到：

- 已有五份材料分批重传后仍是五份，未触发新的报价/围串标分析事实。
- 显式统一分析返回三家报价、三组相似度及持久化报告 `00c8e006-9425-481b-8b14-aa09b4a37be8`，综合等级 WARNING。
- 重试返回同一报告编号，报告服务可回读。
- 独立验收进程退出后再次启动，`RESTART_REOPEN_OK` 确认相同报告编号及五份清单可重开。
- 招标材料保存的 AI 状态是 `AI_TIMEOUT`，本次重开沿用已保存规则结果；真实 AI 合规审查成功 NOT VERIFIED。

本轮没有重置账号、修改中间件配置、执行 DDL、删除历史材料或历史报告。GitHub 普通推送因无法连接远端 443 端口失败，本地提交与测试环境部署独立完成。
