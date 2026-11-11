# 00 Baseline

采集时间：2026-11-11（Asia/Shanghai）。工作目录：`E:\audit-agent-new\.runtime\budget-home-e17-20261014`。

## Git 与工作区

- Branch：`integrate/github-latest-20261105`
- HEAD：`02a0dea7e28a710225fcabd330363133fbb0910e`
- Upstream：本地领先 1 commit（`git status --short --branch`）
- Existing dirty tracked files：18；untracked reports：`SYSTEM_FULL_AUDIT_DISCOVERY.md`、`docs/qa-26/three-module-regression-20261110.md`。未 reset/clean/stash/switch。
- `AGENTS.md` 指定目录 `D:\code\audit_agent_1D` 与实际工作目录不一致，待维护者确认。

## 原有 dirty 文件 SHA256（测试前）

| 文件 | SHA256 |
|---|---|
| `DEPLOYMENT-MEMORY.md` | `B33B2193D2E91275D629B1CFCBC3C643BC88F20EAAE0F0A427019455C6478E77` |
| `backend/src/main/java/com/fuquan/audit/security/domain/PersonalUploadRoles.java` | `CF75B9BFC0F34CEAA27FA8EA83D744A07D7414865D8A4E88DC496D049D42EA41` |
| `backend/src/main/java/com/fuquan/audit/security/infra/PersonalUploadAccessInterceptor.java` | `A77D690A4D9E744947BE45C10D327E667CF075CBB64658637D2656C1F302323A` |
| `backend/src/test/java/com/fuquan/audit/security/infra/PersonalUploadAccessInterceptorTest.java` | `D220842BFE9A58D45E70567638CE15C188217000F7B94FFC06110A5772DFE780` |
| `frontend/src/app/App.tsx` | `1FADDE9411B9C0FEEE75658BDC3B7CF79EFD6BDB4C6E62546367216B31E2CB14` |
| `frontend/src/app/BlacklistAlertPage.tsx` | `A335E6165F8FBD0AF63123542D411725BCA54CF53053352A5B0715B2A3C04724` |
| `frontend/src/app/appShellPresentation.test.ts` | `A43B3ECC6349F44A36F23976D7CD0401CC42B3C931E2B9F352D8A9FD951B12AF` |
| `frontend/src/app/appShellPresentation.ts` | `982187D80212BC4DBFCBAA169CCED363FFA04A437DBA05C8925CED422BABE40D` |
| `frontend/src/app/deliveryNavigation.test.ts` | `EDDD1EB57B74EDA35D0285E033E753754BFF2452749A5E1018E1950CD6269EF7` |
| `frontend/src/app/demoNavigation.test.ts` | `5767C547BC48E80B419440362F859989C4BF7CBADADF2703AF87BD1B7C204A46` |
| `frontend/src/features/contract/components/ContractPortalHome.test.ts` | `AB33864FEDDB7B67DD1E8B0653BDFD673C6F94F51EF50F890C4E431DF0FD2F81` |
| `frontend/src/features/dashboard/DeliveryWorkbenchPage.tsx` | `A130D66803BC1E498F9566DECFA1344A44DBF0F02B5384A45136EB0BF05A35BF` |
| `frontend/src/features/dashboard/workbenchPresentation.test.ts` | `DCE53EEC6BDB4C4CAC19FFF7C8700EE1E22F06155D8C74BE88EF05E5260F655B` |
| `frontend/src/features/risk/RiskAssessmentPage.test.ts` | `EFA5BE809B50B303D95CBE9ED8A15AC956EC8813DBA7C25EB4717F461199DEDA` |
| `frontend/src/features/risk/RiskAssessmentPage.tsx` | `F3A8C1A8783C75C409F84DCB7C84E73F74F90DD72541DEC200FF20F067AEB83D` |
| `frontend/src/features/risk/riskAssessmentProjection.test.ts` | `D938BDE8C6BE7C35BAFE073128C752688511373051272021095B62F82AD55966` |
| `frontend/src/features/risk/riskAssessmentProjection.ts` | `9D0D41C01D5C0BF35EF04324D11A7EB21B7E6364D5606938F4A25E3132319F90` |
| `frontend/src/styles/delivery-visual-v2.css` | `07770F101357CE8297FE640D110A93416B5F628B521D421451B09D4D75967D12` |

哈希对 tracked dirty files 共 18 项；以上报告文件和已有 QA 文件在测试前为 untracked，不属于既有 tracked dirty 内容。

## 本机环境与 26

- JDK：Temurin 17.0.18，本次 Java 使用该 JDK；另有系统默认 Java 21，不用于 Maven 验证。
- Python：Python 3.11.9。为测试建立仓库 `.runtime/qa-full-python311` 隔离 venv，仅安装仓库 `requirements-test.txt`；不含/未替换 Paddle 原生 OCR 包。另有 Python 3.12.14 workspace bundle，不作为项目 3.11 目标测试替代品。
- Node：v24.14.0；前端 `node_modules` 存在。
- 26 `GET /api/v1/health`：HTTP 200，`audit-backend` UP；首页 HTTP 200，静态入口引用 `index-BsidxGmX.js`。
- SSH `admin@192.168.0.26` 登录成功。OS：Kylin Linux Advanced Server V10 (Halberd)；内核 `7.0.2-6-pve x86_64`；16 vCPU；32 GiB RAM；根分区 196 GiB、可用 83 GiB。该主机是 x86_64，不验证 ARM64 性能/兼容性。
- Java：OpenJDK 17.0.2；Python `/opt/python/venvs/audit-ai`：3.11.16；PaddleOCR 3.7.0、PaddlePaddle 3.3.1、FastAPI 0.141.1；Nginx 1.21.5。Nginx、Redis、`audit-backend`、`audit-ai` 和 `audit-qa26-*` units 均 active。DM8 `AUDITDB` 服务 active，但产品版本未确认。
- 默认入口：`/opt/audit-agent/current -> releases/shared-blacklist-20261110-r1`；backend 监听 8080，AI 监听 8001；根 URL 静态入口为 `index-BsidxGmX.js`。
- QA26 入口：Nginx 18082；反代 backend 19151；AI 19153。QA backend 工作目录为 `/opt/audit-agent/pilots/v24-20261030-r1/backend`，profile `dev29`；QA web 静态根目录为 `/opt/audit-agent/pilots/v22-20261029-r1/frontend`，页面引用 `assets/update-v22/index-HK2D8nCS.js`。QA health 返回 HTTP 200。
- **QA 部署版本不一致**：前端 v22、后端候选 v24，且不同于默认入口 2026-11-10 release。经用户明确授权，后续使用 QA API 执行了合成材料低量功能测试；结果仅代表当前 QA backend/API 线，不代表 QA 前端页面与其匹配。
- QA DB env file 为 `/etc/audit-qa26/backend.env`，权限不足；`sudo -n` 要求密码。未读取凭据，也未确认 QA DB/schema/user 与默认服务隔离。因此“服务进程隔离”已确认，“数据库和业务数据隔离”待确认。
- 用户确认该机器为个人测试环境，授权使用现有数据环境开展测试。只新增 `r6` 标识的合成上传/分析记录，没有删除/覆盖既有记录，没有读取或输出业务文件正文，没有修改服务配置、迁移数据库、重启服务或执行故障注入。
- 虽有独立 QA 服务进程和端口，但数据库/schema/文件目录物理隔离仍未证实。本轮只做低量验收，不据此开展并发/容量压力测试；测试服务与版本基线详情见 `08_SYSTEM_RELIABILITY.md`。
