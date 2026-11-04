# 采购需求列表表头离线字体验证（2026-10-10）

## 变更与范围

旧版采购表头 `font-mono text-xs` 在 14px 根字号下为 10.5px，依赖系统中文回退；此前 12px 补丁仍依赖客户端字体。这是代码中可确认的渲染风险，并非仅凭截图确认了甲方浏览器内核错误。

本次只为十列表头增加离线 `Audit Header SC` 字体（Noto Sans SC / SIL OFL 1.1，静态 500 字重，更名子集）。维持 12px / 18px 和原颜色、间距、表格结构。字体 134 字符、13,912 字节。未扩大为全站字体或布局改版。

修改/新增：

- `frontend/src/styles/procurement-table-header.css`：本地字体声明与限定表头的字体规则。
- `frontend/src/assets/fonts/`：WOFF2、许可证、来源和哈希。
- `tools/build-procurement-header-font.py`：子集生成，使用工作区 fonttools 4.66.1 / brotli 1.2.0。
- `tools/build-procurement-header-package.py`：六组已知入口的增量补丁；Linux 控制文件显式 LF；包重新打开逐文件比对。
- `scripts/offline/procurement-header/`：精确表头定位脚本、版本/哈希守卫、原子入口更新、备份和回滚、更新回归测试。
- `tools/test-procurement-header-font.mjs`：浏览器实际字体、文字边界、导航像素及正文不变验证。
- `docs/procurement-header-font-fix.md`：甲方更新和回滚命令。
- `.gitignore`：忽略工作区字体构建依赖和原始下载文件。

沿用前轮已存在的 App 表格专用 class 和 CSS import，未覆盖任何其他未提交变更。检查了 `git status --short`、`git diff` 和 `git diff --check`；未提交、推送。

## 本机浏览器与构建：VERIFIED

环境：Windows，实际 Chromium **109.0.5412.0**、Chrome **155.0.8059.39**；生产前端资源、模拟业务 API、本机静态 HTTP 服务。旧应用 JS/CSS 原字节不变，通过补丁增量资源呈现新表头。

可执行测试（仓库根目录，浏览器路径按本机已有安装填写）：

```powershell
node tools/test-procurement-header-font.mjs <Chromium109.exe> <原前端dist> .runtime/header-font-final/AuditAgent_Header_Font_20261010/variants/r6/dist final109
node tools/test-procurement-header-font.mjs <Chrome.exe> <原前端dist> .runtime/header-font-final/AuditAgent_Header_Font_20261010/variants/r6/dist current
```

实际结果：十列表头逐项 CDP `CSS.getPlatformFontsForNode` 显示 `Audit Header SC`、`isCustomFont=true`，无系统回退字体。1920/DPR1、1536/1.25、1280/1.5、960/2 下十列文字边界均位于单元格内，字重 500、12px、18px。表格正文 HTML/字体/颜色一致，导航解码后的像素一致，零外网资源请求、零 pageerror。PNG 压缩字节不作为像素相等的判断依据。

证据：`.runtime/header-font-browser/final109.json`、`154.json`（该标签运行的实际浏览器版本为 155）、相应四组截图。实际截屏目视核对通过。

`frontend` 下 `npm run build`：PASS，2433 modules，生成独立 13.91 KB WOFF2。仅存在既有大 JS chunk 警告。

`backend` 下 `JAVA_HOME` 指向已有 JDK 17.0.18，执行 `./mvnw.cmd clean verify`：**BUILD SUCCESS，2164 tests，0 failures，0 errors，2 skipped**。初次默认 JDK 21 被 Maven 工具链规则拒绝，随后使用本机已有 JDK 17 完成验证；未安装系统软件或绕过规则。日志 `.runtime/header-font-backend-verify.log`。

## Linux 更新/回滚：VERIFIED

在 26 的真实 Linux Bash / coreutils 运行 `scripts/offline/procurement-header/test-update.sh`。测试覆盖已知版本、重复执行、HTTP 失败自动回滚、手动回滚、未知入口拒绝、用户后改入口拒绝覆盖、包资源篡改、原资源缺失、文件名冲突；Nginx/后端/env 哈希保持一致，服务变更 stub 设为错误退出且未被调用。结果 PASS。

实机完整包校验发现 Windows 默认文本换行导致 Linux 清单把 CR 识别为文件名的一部分。更新在任何入口修改前被阻止。生成器已显式 LF，并对所有 shell/sha256/入口映射检查无 CR。最终包重新解压、`sha256sum -c manifest.sha256` 和两份脚本 `bash -n` 全部 PASS。

## 26 前端部署：VERIFIED

26 是已有开发部署，不具有甲方离线安装完成 marker。开发部署使用限定当前 release `0b64c63-diagnostics-20261010-r6`、要求后端/AI active 的临时部署包装，保留正式甲方 updater 的安装完成守卫。只添加静态资源和原子更新 index；未重启服务。入口备份：`/opt/audit-agent/frontend-backups/header-font-20261010.w55iZQ`。

更新后逐一验证首页、新 CSS/JS/WOFF2 的 HTTP 内容哈希与包一致。实际响应：

```text
GET /api/v1/health -> 200
{"code":200,"message":"success","data":{"service":"audit-backend","status":"UP"}}
GET /api/v1/procurement/projects/source-projects（未登录）-> 401
GET /assets/header-sc-500-5db8f24ccca49acef927.woff2 -> 200
```

原 backend JAR、原前端 JS/CSS、backend env、AI env、Nginx 配置 SHA-256 均与更新前一致；后端/AI/Nginx active。没有更改模型网关地址、密钥、模型名、DM8、Redis 或业务数据。此字体任务没有操作数据库，不重复声称新的 DM8 验收。

## 最终交付物

`.runtime/header-font-final/AuditAgent_Header_Font_20261010.tar.gz`（27,269 bytes）：

```text
SHA-256 1e8b7b5089b945fed7fe0dc07b93d287c31ecc7504aad7a8370444a24d00511f
WOFF2 SHA-256 5db8f24ccca49acef927d35e83151e9b8383bd8eefb2c346be1d07c0e0de1296
```

服务器与本机包哈希一致。旧 CRLF 测试包位于被忽略的 `.runtime/header-font/`，不能交付。最终包无需安装系统字体或访问外网，包含入口备份/回滚脚本；仅支持清单内已知前端入口，未知版本拒绝覆盖。

甲方银河麒麟 ARM64 / Chrome 109.0.5414.120 的显卡、字体策略、真实浏览器缩放：**NOT VERIFIED**。本机 DPI 测试不等价于甲方硬件验证，也不意味着任意更低版本浏览器全部兼容。现场更新后 Ctrl+F5，若异常仍存在，核查 WOFF2 HTTP 200、实际使用字体、100% 浏览器缩放及浏览器控制台字体/CSP 错误。
