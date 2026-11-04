# v23 采购项目金额名称统一

## 修改范围

采购自动分析结果页顶部改为“预算金额”“最高限价”，HTML导出同步。
继续使用整批上传材料归集的项目额度，不依赖页面标题或文件名称提取。
本次没有修改金额识别、证据校验、不同项目隔离或冲突处理逻辑；重复出现的同一额度不累加。
保留v22的历史查询修复、正常项折叠、中文表述和合同八大项。

## 本地验证

- Windows / Java17：backend下 `.\mvnw.cmd clean verify`，3427项，0失败、0错误、24跳过。日志 `.runtime/v23-java-final-verify.log`。
- 其中 `CaseNaturalBudgetTest` 3项通过：自然语言预算、大写金额及限价等角色区分。
- 前端全组846项通过：`node --experimental-strip-types --experimental-transform-types --test <src下全部.test.ts>`。日志 `.runtime/v23-frontend-final-tests.log`。
- `npm run build:customer` 通过，`CHROME109_COLORS_READY 221`，资源目录 `assets/update-v23`。日志 `.runtime/v23-frontend-build.log`。
- AI源码与v20逐文件哈希一致，沿用2016通过/15跳过的既有证据，本次未重跑Python全组。

## 验证边界

本次26的SSH和HTTP端口均连接超时，未上传、未部署v23，远程浏览器和实际模型结果均NOT VERIFIED。
26最后已验收的版本为v22.material-20261029-r1；不可将本地构建或清单称为26部署成功。
甲方ARM64现场执行仍NOT VERIFIED。没有Git提交/推送。

## 交付

新名Update_v23.tar.gz。完整包含v22已有修复以及本次金额名称修改。
安装脚本、schema、SQL、Java17检查器与v15逐字节一致；没有新增DDL、备份证明或恢复JSON门槛。
现场模型配置、数据库、Redis、Nginx和业务文件保留。离线OCR方向权重保留。
解压到新目录，进入包含update.sh的目录，执行 `sudo bash update.sh`；成功以UPDATE=PASS为准，之后Ctrl+F5。
最终包验证记录：`evidence/v23/package-verification.json`。
