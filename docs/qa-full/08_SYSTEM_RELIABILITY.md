# 08 System Reliability

状态：用户确认 26 为个人测试环境，可使用现有数据环境测试。本轮只新增带 `r6` 标识的合成材料测试记录；没有删除或覆盖既有记录，没有修改配置、迁移数据库、重启服务或执行压力/故障注入。

既有历史证据（仅供参考，非本轮新结果）：2026-11-10 只读健康及三模块历史 API 并发阶梯总计 500 请求均 HTTP 200，并发 12 下 P95 74–103 ms。该结果不代表写入、DM8、文件、OCR、模型或系统容量。

源码可见 DM8 持久任务、lease/operation key、模块级 worker 和有界 OCR capacity。合同 ProjectFile/ReviewRun 的恢复/重开路径较完整；预算/采购模块实现不同。仍需隔离环境执行重复提交、响应未知、服务/Python 重启、lease 过期、旧 worker 迟到、单文件失败、刷新重开和事务失败注入。验证中不得删除不确定归属的数据，UNKNOWN 不自动重发。

本轮 Windows Python 3.11 全量 pytest 中 checkpoint 测试通过；单独重跑 `test_material_adaptive_limits.py -k checkpoint` 为 2 passed、39 deselected。历史 5 failures 未复现；没有 POSIX 环境，尚不能解释历史差异是否由平台引起。

26 SSH 已连通并核实运行资源。QA web v22 与 backend v24 不一致；默认入口运行的是另一条部署线。故本轮结果只适用于 QA API 服务线，不能代表浏览器 QA 页面和 API 完全匹配，也不能外推到默认入口/甲方环境。用户授权后执行了真实 DM8 写入的合成业务上传测试；没有 DBA 级别 DB/目录隔离证明，故未执行高并发、故障注入或容量压力测试。OCR HTTP 与合同任务成功到达原文 READY；模型依赖 502 导致预算 UNKNOWN 和采购 DATA_INSUFFICIENT/合同 PARTIAL。资源值见 `00_BASELINE.md`，DB 连接数和积压阈值仍待基线核实。
