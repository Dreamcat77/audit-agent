# 预算100万元额外审批门槛

2026-10-30 用户明确：100万元本身也触发额外审批。

- 用户选定记录后，逐条使用 BigDecimal 合计申报金额，不增加项目名、日期或部门筛选。
- 新分析仅区分低于100万元、达到或超过100万元。后者给出额外审批提示，不编造审批环节和依据条款。
- 金额缺失或为负数不形成完整分档，也不提示已排除额外审批。
- 自动同项目多笔小额申报累计检查同步使用含边界的100万元门槛；不同项目不混算。
- 上传制度中的明确审批要求继续单独核验，不改写制度本身。
- 新结果版本 SELECTED_SUM_V3；历史结果保留原快照，前端继续支持 SELECTED_SUM_V2。
- 无数据库迁移；此次未修改OCR或模型提取。

验证命令：backend 下 `.\mvnw.cmd clean verify`；frontend 下对 src 全部 .test.ts 执行 `node --experimental-strip-types --experimental-transform-types --test`，再执行 `npm run build:customer`。

边界覆盖：999999.99、1000000、1000000.00、1000000.01；多笔600000+400000及999999.99+0.02；缺失金额和负数；新结果保存后重读一致。命令输出位于 `.runtime/budget-1m-final-verify.log`、`.runtime/budget-1m-frontend-tests.log` 和 `.runtime/budget-1m-frontend-build.log`。

26部署及真实HTTP、Chrome109验证结果见 `evidence/budget-1m/`。模型网关此前连接超时；本次金额分档为确定性计算，不将其验证结果冒称模型提取质量通过。

## 本轮实际验证

- Windows / Java17：clean verify共3445项，0失败、0错误、24跳过。
- 前端846项通过，customer构建通过；Chrome109兼容颜色处理221处。
- 26默认版本budget-1m-20261030-r1：运行JAR和前端HTTP资源哈希一致，配置保留，无数据库结构变更。
- 26实际部署JAR执行六项边界用例通过：低于/等于/高于100万元、多笔累计及缺失金额。
- 真实Chrome109.0.5412.0页面勾选、执行分析、读取保存结果通过，无脚本错误。具体金额及新分档接口记录见browser-http.json。

首次浏览器验证脚本漏点“选取项目进一步分析”折叠入口，修正测试步骤后通过，未为测试修改页面行为。26系统python3为3.7，暂存脚本改用已有Python3.11虚拟环境后部署成功。
本次没有Git提交或新甲方更新包。
