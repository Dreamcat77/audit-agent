# 前端 API 调用文档

本文档描述当前前端公共 HTTP、认证、F01 和 F02 的真实状态。接口是否可用必须以后端实际 Controller 和 DTO 为准，不能仅根据前端已存在的方法或类型来判断功能已经完成。

## 文件结构

```text
src/api/
├── client.ts      # 公共 HTTP Client / Session / CSRF
├── auth.ts        # login / me / logout
├── types.ts       # F01 及预留接口类型
├── audit.ts       # F01 及未来功能 API 调用
├── index.ts       # 公共导出
└── examples.tsx   # 调用示例
```

F02 不放在 `src/api/audit.ts` 中。F02 的业务数据访问位于：

```text
src/features/contract/data/
├── contractDataSource.ts
├── httpContractDataSource.ts
└── contractSourceMap.ts
```

F02 通过 `ContractDataSource` 抽象在 DEV Mock 与真实 HTTP 之间切换。Mock 只允许在 DEV 环境启用；其他环境强制使用真实 HTTP 数据源。

## 开发配置

开发环境在 `frontend/.env.local` 中配置：

```dotenv
VITE_API_BASE_URL=
VITE_API_PROXY_TARGET=http://127.0.0.1:8080
VITE_AUTH_MODE=session
VITE_CONTRACT_DATA_SOURCE=http
```

- `VITE_API_BASE_URL` 为空表示前端统一请求同源相对路径 `/api`，不得通过修改 `client.ts` 写死服务器地址。
- Vite 开发服务器通过 `VITE_API_PROXY_TARGET` 将 `/api` 代理到 Spring Boot。
- `.env.local` 仅用于本机运行环境配置，不提交 Git，也不得在其中记录或提交账号、密码、密码 Hash、Token 或 Cookie。
- 本地 Session 联调时，后端必须允许 `http://localhost:5173` 的 CORS origin；这里只记录运行环境要求，不记录任何认证凭据。
- `VITE_CONTRACT_DATA_SOURCE=mock` 仅用于 DEV 演示，`http` 用于真实 F02 后端联调。

正式部署采用同源入口代理；
当前过渡环境可使用 Nginx，目标信创环境计划使用 TongHttpServer。
前端始终只访问相对路径 `/api`，不得依赖具体反向代理产品或后端服务器地址。

## 认证状态

当前认证方式是 **Session Cookie + CSRF**，不是 JWT 或 Authorization Bearer。
公共 Client 的所有请求都使用 credentials: "include"；
存在有效 Session 时，由浏览器自动携带 Session Cookie。
POST、PUT、PATCH、DELETE 请求会先获取 CSRF 信息，再使用后端返回的动态 Header 名称发送 Token。

| HTTP 方法 | 接口 | 用途 |
| --- | --- | --- |
| GET | `/api/v1/auth/csrf` | 获取 CSRF Header 名称和 Token |
| POST | `/api/v1/auth/login` | 登录并建立 Session |
| GET | `/api/v1/auth/me` | 恢复或查询当前 Session 用户 |
| POST | `/api/v1/auth/logout` | 退出并失效 Session |

## 后端 API 状态

### 当前已实现 / 当前可联调

#### F01 预算审批校验

| 前端方法 | HTTP 方法 | 后端接口 | 状态 |
| --- | --- | --- | --- |
| `checkBudget(procurementId)` | POST | `/api/v1/budget/check` | B 分支已实现，待纳入统一集成分支 |

请求体：

```json
{
  "procurementId": "<uuid>"
}
```

调用示例：

```typescript
import { checkBudget } from '@/api';

const result = await checkBudget('<uuid>');

console.log('采购ID:', result.procurementId);
console.log('是否全部通过:', result.allPassed);
console.log('校验总数:', result.totalChecks);
console.log('通过数量:', result.passedChecks);

result.items.forEach((item) => {
  console.log(
    item.checkType,
    item.passed ? 'PASS' : 'FAIL',
    item.actualValue,
    item.expectedValue,
  );
});
```

`batchCheckBudget(procurementIds)` 当前不对应后端 batch API。它是前端便利方法，会对每个 ID 分别调用 `checkBudget`；当前不存在独立的后端批量预算校验接口。

#### F02 合同条款量化一致性审查

| HTTP 方法 | 后端接口 | 状态 |
| --- | --- | --- |
| GET | `/api/v1/contracts/{uuid}` | LOCAL HTTP E2E VERIFIED |
| POST | `/api/v1/contracts/{uuid}/agent-review` | LOCAL HTTP E2E VERIFIED |
| GET | `/api/v1/contracts/{uuid}/agent-results` | 用于恢复 DM8 已持久化的最新 F02 结果 |

`POST /api/v1/contracts/{uuid}/agent-review` 必须携带非空的 `Idempotency-Key`。同一次前端审核操作的身份按当前 `userId` 与 `contractId` 隔离，只持久化在 `sessionStorage`，不包含合同业务数据；网络失败、切换页面或刷新后的重试继续复用该 Key。成功收到该操作的 `ReviewResult` 后清除 Key；后端明确返回本次 Key 已失败或 fingerprint 冲突时也会终结并清除 Key，等待用户明确"重新发起审核"。F02 页面通过 `ContractDataSource` 获取合同和最新结果并执行审核，不通过 `src/api/audit.ts` 调用。

F02 运行模式：

DEV 演示：

```dotenv
VITE_AUTH_MODE=demo
VITE_CONTRACT_DATA_SOURCE=mock
```

本地真实联调：

```dotenv
VITE_AUTH_MODE=session
VITE_CONTRACT_DATA_SOURCE=http
```

当前已经完成并验证：

- Session/CSRF 登录；
- Session 恢复；
- Logout；
- 运行中收到 401 后自动返回 LoginPage；
- F02 GET contract；
- F02 POST agent-review；
- Idempotency-Key；
- RED 失败案例本地 HTTP E2E。

以上状态仅表示 LOCAL 环境验证结果，不代表 dev29、DM8、Redis Session 或 customer 环境已经完成验证。

### 前端预留接口 / 后端未实现

以下方法和路径保留在前端用于开发参考，不属于当前正式后端接口清单：

| 功能 | 前端方法 | 当前前端预留路径 | HTTP 方法 |
| --- | --- | --- | --- |
| F03 供应商黑名单 | `checkBlacklist(data)` | `/api/v1/blacklist/check` | POST |
| F05 项目拆分风险 | `checkSplitRisk(data, idempotencyKey)` | `/api/v1/budget/split-risk/check` | POST |
| F07 合同结构对标 | `checkContractStructure(data)` | `/api/contract-structure/check` | POST |
| F09 合同期限审查 | `checkContractPeriod(data)` | `/api/contract-period/check` | POST |
| F11 围串标识别 | `checkCollusion(data)` | `/api/collusion/check` | POST |
| F13 附件审查 | `checkAttachment(data)` | `/api/attachment/check` | POST |
| F15 条款风险提示 | `checkClauseRisk(data)` | `/api/clause-risk/check` | POST |

需要特别注意：

- 当前这些内容只是前端预留；
- 当前 Java backend 不保证存在对应接口；
- 调用未实现接口可能返回 404；
- 不得据此判断对应功能已经完成；
- 后续开发必须以实际 backend Controller 和 DTO 为准。

## 使用注意事项

1. API 调用应处理 `ApiError`，并为页面保留明确的 loading 和 error 状态。
2. 401 表示 Session 未认证或已失效，公共认证状态会自动回到 LoginPage；403 表示权限不足，不应清除当前登录态。
3. 普通对象请求体由公共 Client 序列化为 JSON；FormData 不应手工设置 `Content-Type`。
4. HTTP 模式失败时不得静默切换到 Mock。
5. 不要在源码、文档或 Git 配置模板中记录真实账号、密码、密码 Hash、Token、Cookie、客户 IP 或敏感地址。

## 下一步

1. 将 F01 backend clean 分支纳入统一 integration/main。
2. F02 继续保留 Mock/HTTP 双数据源。
3. 在 dev29 环境验证 DM8、Redis Session 和真实部署入口。
4. F03-F15 随实际 backend 实现逐项启用。
5. TongHttpServer 到位后，验证 `/api` 反向代理以及 Cookie、CSRF、Idempotency-Key、Request-Id 等 Header 的透传。
