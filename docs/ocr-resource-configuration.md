# OCR 并行资源配置

预算、采购、合同共用 OfficeDocumentTextReader。PDF 先逐页读取原生文字，扫描页和大图混排页再交由独立 OCR 进程处理。每个 OCR 进程保持 `cpu_threads=1`、MKLDNN 关闭，不在多个线程之间共享 Paddle 实例。

## 配置

Java：`AUDIT_OCR_PAGE_CONCURRENCY`，默认 1，允许 1～4。

Python：`AUDIT_OCR_WORKER_PROCESSES`，默认 1，允许 1～4。生产调整时与 Java 的上限一起配置。

Python 在启动时，根据 CPU affinity、可见 cgroup v2 各级配额、MemAvailable 和内存规划估算实际进程数。取请求上限、CPU 容量和内存容量中较小者。无法确认资源时保留单进程。无控制器文件的子组继承父组限制，存在但不可读或非法的控制器配置不会授权扩容。

内存规划参数：

- `AUDIT_OCR_MEMORY_BUDGET_MB=16384`：OCR 总体规划预算。
- `AUDIT_OCR_MEMORY_RESERVE_MB=4096`：为系统其他服务保留的可用内存。
- `AUDIT_OCR_WORKER_MEMORY_MB=4096`：每个进程的规划占用，包括可能加载的精度复读模型。

这些参数用于启动时准入估算，不是操作系统对 RSS 的硬限制，也不承诺运行期间自动伸缩。部署后应观察实际峰值和其他业务负载；更改配置后重启生效。甲方 ARM64 未经同平台测量时仍使用默认 1，不照搬 26 的四路配置。

## 完整性与恢复

全局槽位先于任务提交获得，跨文件共享上限。PDFBox、结果集合和检查点只由文件所属线程修改；工作进程仅处理单页。按完成顺序保存检查点，最终按原页码排序，慢页不会阻挡其他槽位继续工作。检查点允许不连续页号，恢复按已完成页集合执行。

线程中断、检查点写入失败或租约失效后停止后续调度；已经发出的页面必须结束后才释放其输入文件。结果不明的 OCR 不自动重发或替换进程。所有者退出无法确认时沿用原有隔离保留机制。

不改前端、业务数据库或模型判定规则。日志只记录阶段、数量、耗时、状态和资源上限，不输出正文、实际金额、文件名、模型凭据。

## 回归命令

后端：在 `backend` 中执行 `mvnw.cmd clean verify`。

Python：在 `services/audit-ai` 中执行 `python -m pytest -q tests/test_ocr_pool.py tests/test_ocr_process_adapter.py tests/test_ocr_orientation.py tests/test_ocr_fast_runtime.py tests/test_config.py tests/test_material_adaptive_limits.py`。

真实文件验收应覆盖混排长 PDF、侧向扫描、原生文字旁路、末页读取、并行输出顺序、恢复后的页面集合和忙时查询响应。OCR 验收与真实模型抽取验收分别记录，不以健康检查成功代替业务验收。
