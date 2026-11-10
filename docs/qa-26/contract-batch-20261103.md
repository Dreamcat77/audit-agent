# 合同批次上传修复（2026-11-03）

用户撤销Update_v24对齐，维持65464825最新基线。本次仅调整合同前端上传与顶部提示，没有预算、采购、Java、AI或数据库业务修改。

原问题：合同页读取旧上传记录，把所有选文件操作强制当成旧任务重试，新文件无法通过原文件匹配，按钮一直变成“选择原文件继续”。

现在一次明确选择上传的一批就是一个项目。新批次启动新项目，批内全部文件进入同一项目，不按文件名判断项目关系。已有未确认操作保持原标识和记录，不阻止新批次。发送中的重复操作仍共用一次请求。移除顶部通用上传说明与旧任务提示，保留实际上传忙碌状态、必要错误、结果八大项及正式审核确认。

修改文件：

- `frontend/src/features/contract/components/ContractAnalysisWorkspace.tsx`
- `frontend/src/features/contract/contractMaterialIntakeOperation.ts`
- `frontend/src/features/contract/retainedContractMaterialIntake.ts`
- `frontend/src/features/contract/contractMaterialIntakeOperation.test.ts`
- `frontend/src/features/contract/contractAnalysisPresentation.ts`
- `frontend/src/features/contract/contractAnalysisPresentation.test.ts`

验证：

- JDK17，在backend运行`./mvnw.cmd clean verify`：3445项，0失败、0错误、24跳过。
- frontend运行全部`*.test.ts`，Node参数`--experimental-strip-types --experimental-transform-types --test`：848通过。新增回归包括旧任务结果未知、新批同名多文件、损坏旧记录、空选择/不支持文件和发送中重复触发；既有原操作恢复/幂等回归继续通过。
- frontend运行`npm run build:customer`：通过，包含Chrome109颜色、currentcolor及本地字体处理。
- Chrome109.0.5412.0候选测试使用本地构建静态资源和26真实API；随后默认入口不拦截任何请求再次测试。每轮实际创建两批，第一批两份文件共用项目，第二批同名文件创建另一个项目；创建HTTP200、三份接收202且有真实fileId/jobId，数据库投影重读文件数为2/1。
- 浏览器注入旧的有效及损坏上传记录，确认新批次不被阻挡，旧记录字节保持原样；自动打开结果页，八项始终存在，返回历史及刷新重开通过。1440、1024、390宽度无页面横向溢出，无浏览器脚本错误。
- 26前端静态资源逐项HTTP哈希及新入口一致，后端JAR哈希保持原值。backend/AI/nginx均active，无重启、DDL、配置改动。

202表示文件已接纳，并不表示模型分析完成。本轮验证上传、批次归属、结果页与历史流程，新的模型提取质量NOT VERIFIED。

未制作甲方包，未提交或推送Git。与任务无关的旧证据修改保持原样。
