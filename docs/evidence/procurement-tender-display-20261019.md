# 采购相同提示合并与来源定位展示

## 范围

仅调整前端展示，不改变评分、数据库、报告确认事实或原始证据。

同一段的“必须使用某品牌”和“仅限某品牌”会命中不同内置规则（F10 对应历史技术编码 BRAND-002 / BRAND-005）。相同类别、风险等级、命中文本、来源位置和建议的提示，在报告摘要和明细中展示一次；不同类别、等级、位置、原文或建议不合并。保存的规则命中数组不修改。

风险摘要、报告明细和单文件详情不再渲染技术定位符。已知 PDF 页码仍可作为自然语言位置显示。

## 修改文件

- `frontend/src/features/procurement/review/tenderFindingDisplay.ts`
- `frontend/src/features/procurement/review/procurementReferenceView.ts`
- `frontend/src/features/procurement/review/ProcurementReviewSteps.tsx`
- `frontend/src/features/procurement/ProcurementTenderReviewPanel.tsx`
- 对应单元测试及 `tools/test-budget-material-background.mjs` 的浏览器回归断言。

## 已执行验证

Windows / Node，前端全量：`node --experimental-strip-types --test @tests`，其中 tests 为 `rg --files src` 筛选的全部 `.test.ts`。747 项通过，0 失败。新增重复提示和不同风险不得合并的反向用例。`npm.cmd run build` 成功，既有大 chunk 提示仍存在。

```powershell
node tools/build-procurement-browser.mjs frontend/dist .runtime/review-20261018/frontend-tender-display-chrome109
node tools/test-budget-material-background.mjs E:/audit-agent-new/.runtime/browser-compat-test/chrome-win/chrome.exe .runtime/review-20261018/frontend-tender-display-chrome109 tender-display-109
```

真实 Chrome 109.0.5412.0 浏览器运行最新兼容构建，合成 API seam：相同规则提示在摘要、明细各只出现一次，显示风险 1 条；定位符不展示；复制、严重风险确认及刷新后确认状态保留均通过，浏览器 errors=[]。目视检查截图，页面布局和样式未更改。

证据：`.runtime/review-20261018/frontend-tender-display-tests.log`、`frontend-tender-display-build.log`、`tender-display-chrome109.log`；截图及 JSON：`.runtime/budget-background-browser/tender-display-109*`。

按仓库要求在 `backend` 使用既有 JDK 17 执行 `.\mvnw.cmd clean verify`，BUILD SUCCESS，耗时 3:59。日志：`.runtime/review-20261018/backend-tender-display-verify.log`。本轮未修改后端业务规则。

## 现有 F11 判断边界

围串标为确定性辅助评分：投标文本按相邻双字符进行 TF-IDF 及余弦相似度，取最高文件对；相似度 85% / 70% / 50% 分别加 40 / 25 / 10 分。报价 CV（总体标准差除均价）低于 5% 加 25，低于 10% 加 10；需至少三家且报价供应商集合与投标人集合一致。重复非空 IP 加 20，重复非空经办人加 15，各项只加一次。总分最多 100，70 起为严重、40 起为一般风险。统一材料入口的 IP/经办人取自材料显式字段，不是当前上传者浏览器的地址。

拆分采购按分析时所在年度、同科目和同供应商的最新询比价记录，检查包含当前项目的 30 天窗口；至少两条记录。DM8 查询排除采购方式保存为“公开招标”的记录。报价累计达到内置 5 万 / 20 万 / 200 万门槛的 90% 即提示，接近分值为 55 / 60 / 65，达到分值为 70 / 80 / 90，80 起为严重风险。每个供应商保留风险最高的窗口。

这些内置门槛及权重未证明适用于甲方；文本相似可能来自公共模板，IP/经办人一致也不等同违规。拆分依据是系统报价与记录时间，并非已证明的成交支出、实际采购日期或故意拆分事实；当前未要求每笔都低于门槛，也未证明属于同一个实际采购需求。本轮没有擅自修改这些业务规则。

未部署服务器或甲方环境，真实 DM8 / 甲方浏览器端验收：NOT VERIFIED。
