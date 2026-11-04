# 受限终端的浏览器兼容处理

甲方终端安装浏览器、切换内核均受限制。本轮不要求终端变更，不调整上传、审查或查看结果的业务流程，也没有部署新服务。

## 当前问题与边界

原入口只有 `type="module"` 脚本与空的 React 根节点；IE 等无法执行该入口时，可能没有任何可见内容。现有 Chrome 109 适配主要追加颜色回退，不能代替旧内核的脚本、CSS 层、布局与浏览器 API 兼容。

项目目前使用 React 18、React Router 7、Tailwind 4。此次构建目标仍为 Chrome 109，没有声称将完整业务应用移植到 IE 或更旧 Chromium。要在不能改变的终端上完整运行，需先取得真实内核/能力分布，再选择并验证主要旧内核的构建、样式及组件方案。若明确要求 IE 业务操作，需要单独评估兼容界面或单位批准的服务端浏览器方案；不能仅增加一个 polyfill 就宣称完成。

## 已完成的基础处理

- HTML 初始即显示简洁提示，不依赖 React、现代 CSS 或模块脚本；禁用 JavaScript 时也有可见说明。
- `public/browser-support.js` 是经典 ES5 脚本，检测当前内核能力和 IE 文档模式，不靠“Chrome/360”产品名称猜测能否运行。不支持的基础能力会给出说明，暂不启动业务应用；可选样式能力只记录，不单独阻断。
- 加载失败、渲染失败、启动缓慢分别处理；启动超时后若实际完成加载，提示会正常收起。不会自动清理浏览器存储或循环刷新业务页面。
- 独立 `/browser-check.html` 不导入 React 或业务系统，可直接打开并复制诊断信息。剪贴板不可用时，提供可手动全选复制的文本框。
- 诊断内容只主动读取浏览器标识、文档模式、窗口大小及能力结果，不读取账号、合同、Cookie、存储或访问 URL；异常正文不写入诊断。复制/提交由用户手动完成，没有新增自动上传接口。
- Vite 明确指定 Chrome 109 的 JavaScript/CSS 构建目标；统一 CSS 输出，使现有颜色回退能覆盖动态加载代码的样式。普通生产构建也自动执行颜色处理，不再只依靠单独命令。
- 打包工具保留检测页与经典脚本；颜色辅助脚本在缺少基础 API 时直接退出。

检测页“基础能力存在”不是完整业务验收结论；User-Agent 也可能被修改，不能据此认证浏览器或用户。

## 无需逐人询问版本的统计方法

若服务器保留 Nginx 常见 combined 格式或带 `http_user_agent` / `user_agent` 字段的 JSON 访问日志，可在已有 Node.js 的维护/前端构建环境执行：

```sh
node tools/browser/summarize-browser-access.mjs /path/to/access.log > browser-summary.json
```

也可从标准输入读取：

```sh
node tools/browser/summarize-browser-access.mjs -
```

工具只输出内核、主版本、疑似 IE 兼容模式及请求次数，不输出 IP、请求地址、账号、原始 User-Agent 或日志正文。统计单位是请求次数，不是独立用户或设备；高频请求可能放大某个版本的占比。无法识别的版本及日志行单列，不凭空推断。360 的品牌版本不能代替实际 Chromium/IE 内核版本。

维护机器没有 Node.js 时，可在现有前端构建环境分析获准使用的日志；无需在甲方终端安装工具。原始日志可能包含敏感信息，不应上传到公开网站；通常只需提供汇总 JSON。日志格式不匹配时检查 `unparsedLines`，不要把零条成功解析误认为没有旧浏览器。

对仍有问题的终端，更新静态文件后打开同一系统域名下的 `/browser-check.html`，复制检测信息即可，不需要用户自己查浏览器版本。此次没有读取真实客户服务器日志，也未取得客户内核分布，因此尚未选择一个未经证实的更低支持版本。

## 验证记录（2026-11-04）

- 9 项 Chromium 浏览器检查通过：正常启动、模拟 IE 文档模式、缺失 CSS 能力、加载失败、渲染失败、慢启动恢复、经典脚本请求失败、检测页手动复制，以及禁用 JavaScript。
- 4 项日志统计测试通过，覆盖 IE 兼容模式、360/Edge Chromium 标识、JSON/combined 格式、无效输入及输出内容限制。
- 两个经典脚本通过 Acorn `ecmaVersion: 5` 语法解析；这不等于真实 IE 渲染验证。
- 用实际启动入口、启动组件和构建插件构建隔离的最小 Vite 页面；验证 CSS 回退、静态资源、重复处理幂等、打包复制、浏览器打开及真实 `curl` 访问检测页。该页面不是完整业务应用。
- 后端完整 `clean verify`：3457 项，3432 通过、24 跳过、1 项既有前端 `client.ts` 固定哈希守卫失败；未增加新的后端失败。

相关测试命令：

```sh
node --test tools/browser/summarize-browser-access.test.mjs
node --test frontend/tests/browser-support/browser-support.browser.test.mjs
```

浏览器测试需已有 `esbuild`、`playwright-core`、React 与 Chromium；可用 `AUDIT_BROWSER_TEST_NODE_MODULES` 指定隔离工具目录，`AUDIT_BROWSER_EXECUTABLE` 指定浏览器。

完整业务生产构建仍 **NOT VERIFIED**：当前工作区未能完成前端依赖安装，`vite` 不可用；锁定的 SheetJS 下载域名 `cdn.sheetjs.com` 当前返回 CONNECT 403。没有替换或降级该依赖以伪装构建通过。真实 Chrome 109/更旧 Chromium、IE、360 本轮未实测，旧 Chromium 测试二进制下载域名同样受网络限制。客户部署 **NOT VERIFIED**。

后续应先汇总现场内核分布，在依赖可正常安装的构建环境完成完整构建，再对选定的真实内核验证登录、上传、历史查询、原文高亮与报告页面。现有诊断和防白屏处理不能作为这些业务已兼容的证明。
