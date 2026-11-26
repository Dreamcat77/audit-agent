# 2026-11-26 本机26复测执行包

26验收状态为 `PENDING_LOCAL_26`。本包供能够访问26的本机 Codex 执行。仅对冻结合成材料使用此流程。云端结果与本机26结果分别记录，甲方现场仍待验收。

## 对齐源码和冻结材料

先在本机唯一源码目录 `E:\audit-agent` 检查 `git status --short`、`git rev-parse HEAD` 和 `git log -1 --oneline`。保留已有修改。通过正常 Git 同步取得云端交付提交，并核对完整 SHA。禁止从历史备份覆盖当前工作树。不要自动 stash 或 reset 用户工作。

在仓库根目录使用已有 Python 3.11 应用虚拟环境执行。检查工具使用已由 `services/audit-ai/requirements-test.txt` 声明的 pypdfium2 和 Pillow，不需要模型或服务器凭据。

```powershell
$qaPython = '填写已有Python3.11应用虚拟环境的python.exe绝对路径'
& $qaPython tools/qa26/check_field_simulation.py --output .runtime/qa26-r3-materials.json --rotation-fixture .runtime/field-simulation-20261126-r3/pdf-pages/F26C24/廉洁保密协议-01.png
if ($LASTEXITCODE -ne 0) { throw '冻结材料检查失败' }
& $qaPython -m unittest discover -s tools/qa26 -p test_check_field_simulation.py -v
if ($LASTEXITCODE -ne 0) { throw '检查工具测试失败' }
```

预期为3批、每批4份、共12份文件，所有摘要与 manifest 一致。manifest SHA-256 为 `a1ca04a010d1179f462ab21935101c1237859826736277f91b36cd10cc9e419f`。各材料摘要见 manifest 和本轮材料检查记录。上传时只选择对应批次4个文件，manifest 和测试真值不进入材料或模型提示词。

## 本地回归命令

在 JDK17 环境执行后端门禁。Linux/macOS 用 `./mvnw clean verify`，Windows 用以下命令。

```powershell
Push-Location backend
try { .\mvnw.cmd clean verify; if ($LASTEXITCODE -ne 0) { throw '后端门禁失败，请记录失败项' } }
finally { Pop-Location }
```

前端按锁文件安装，网络需要 `registry.npmjs.org` 和 `cdn.sheetjs.com`。不要替换锁定的 xlsx 包或关闭校验。

```powershell
Push-Location frontend
try {
  npm.cmd ci
  if ($LASTEXITCODE -ne 0) { throw '依赖安装失败' }
  npm.cmd run build
  if ($LASTEXITCODE -ne 0) { throw '生产构建失败' }
  npm.cmd run build:customer
  if ($LASTEXITCODE -ne 0) { throw 'customer构建失败' }
} finally { Pop-Location }
$env:AUDIT_TEST_BROWSER = '填写实际Chrome109的chrome.exe绝对路径'
node tools/test-original-evidence-rotation-browser.mjs
if ($LASTEXITCODE -ne 0) { throw '旋转夹具失败' }
node tools/test-office-evidence-preview-browser.mjs
if ($LASTEXITCODE -ne 0) { throw 'Office夹具失败' }
```

记录浏览器实际版本。Office夹具覆盖分页、工作表/单元格、重开、原件入口和请求数；旋转夹具覆盖四方向、页码和高亮保持及原件请求数。这些是组件夹具，实际26页面仍需下面的浏览器验收。

## 26只读预检

在可访问26的本机执行 SSH，使用可信主机指纹，密码仅交互输入。禁止把密码写入命令、脚本、报告或Git。

```powershell
ssh admin@192.168.0.26
```

在浏览器打开已有26入口，确认登录角色及当前发布实例。历史文档同时存在默认80和独立18082入口，必须以当前服务与发布目录为准，避免错测旧实例。确认入口后运行以下只读健康检查。

```powershell
$qaBase = Read-Host '输入已核实的26 HTTP入口，包含端口'
curl.exe --fail --silent --show-error --max-time 15 "$qaBase/api/v1/health"
if ($LASTEXITCODE -ne 0) { throw '26健康检查未通过' }
```

在服务器只读核实服务工作目录和真实进程对应的发布目录，再计算后端JAR、前端index及其引用资源的 SHA-256。记录应用部署来源 SHA 与构建资源哈希，配置中存在的版本字符串不能替代文件哈希。检查活动上传、解析、分析和worker任务。仅列出数量、状态和必要任务ID，禁止导出连接串、环境变量值或业务正文。不要运行 `remote_install.py`、`advance_r3.py` 等历史部署自动化来完成只读检查。

优先通过三模块历史列表查找 F26B44/F26P24/F26C24 及其项目名，打开现有结果，记录真实批次UUID、文件UUID、任务状态和分析版本。对活动任务持续观察至终态，禁止取消其他任务。未验证全局无活动任务前，不部署、不重跑。

确认结果版本是否包含预期修复。若旧结果需要重新分析，先记录旧结果、现有文件哈希和版本，再使用唯一后缀，例如 `F26C24-r3-retest-YYYYMMDD-HHmmss`。禁止覆盖旧结果或只因历史结果失败重复提交同一未知操作。

## 逐项断言及源位置

以下段落号按 DOCX `word/document.xml` 中全部 `w:p` 的顺序计数，含表格段落。Excel位置为原工作簿单元格。它们用于人工核验来源，服务器的解析字符偏移必须另行从实际解析结果核对，不应复用本地段落号或PDFium字符数。

| 断言 | 来源文件及位置 | 预期及结果映射 |
| --- | --- | --- |
| B1 | 关于执法终端及回传能力建设的请示.docx 第5段；项目明细表.xlsx，项目明细!H5:H9 | 四个申报科目，合计490000元，declaredAmount及申报合计 |
| B2 | 项目明细表.xlsx，项目明细!I5:I9、J5:J9 | 审定476000元、下达464000元，各列单独保留；申报高于下达26000元 |
| B3 | 年度财政支出预算预通知书.pdf 第1页；项目明细表.xlsx，D5:G8 | 年度单位86000000元不得作为项目金额，相同财政分类代码不得合并四个科目 |
| B4 | 松浦信息化项目经费复函.pdf 第1页 | 真实OCR读取、编号/年度/单位关联及用途范围；未执行OCR时保持待验证 |
| P1 | 前滩设备巡检竞争性磋商文件.docx 第6—7段 | budgetAmount=2200000、ceilingAmount=2100000；资金构成1800000/400000不重复加入总预算 |
| P2 | 同文件第6段 | 实际标签为财政资金/其他资金。现有确定性提取仅匹配国库资金/自筹资金；与manifest的treasury/selfFund角色存在待确认映射。核查真实模型及证据输出，不能仅凭金额替换标签 |
| P3 | 供应商报价及范围响应.xlsx，报价汇总!C5:C7、D7、E5:E7；费用组成!B5:E7 | 三报价2040000/2080000/2160000，超最高限价60000元；第三报价耗材和备件另计，期限12个月 |
| P4 | 采购文件更正公告.pdf 第1页；采购代理委托协议.docx | 更正后的地域要求生效，代理机构不能成为供应商。评分/资格按实际文件逐项核对 |
| C1 | 合同书.docx 第3—4、7段 | 编号/双方正确；合同2100000元，大写2090000元，相差10000元 |
| C2 | 合同价款明细表.xlsx，价款明细!C5:E8 | 分项合计2050000元，与正文差50000元；F02_LINE_SUM_MATCH、checks/findings及页面均核对 |
| C3 | 中标通知书.pdf 第1页；合同书.docx 第7段 | 中标2060000元，合同高40000元；不得和其他差异混为一个口径 |
| C4 | 四份合同材料各自报告 | Report.categories为八大项；documents[].sourceChars、reviewedChars、fullyReviewed真实，PARTIAL不能伪装完成 |
| C5 | 廉洁保密协议.pdf 第1页；合同正文附件约定 | 已上传附件不得仅因标题引用误判缺失；扫描文件进入OCR/分析的状态可解释 |

每项标记 PASS、FAIL、PARTIAL 或 PENDING，记录实际API结果字段路径、证据文件UUID、页码/工作表/单元格及服务器解析偏移。源材料不存在的角色或规则条件保持未判定。对每个发现至少核对一次原文，分别标记解析遗漏、语义误判、规则未执行、报告未展示、前端未加载。

逐批记录4个文件的上传状态、解析字符数、分析字符数、覆盖状态、处理耗时与错误阶段。用实际原文核对证据偏移，采样结论保留证据摘要或哈希，禁止回传原文段落和模型输入输出。

在真实26页面分别检验预算、采购、合同的批次与文件关联、历史重开、取消本轮创建的专用测试任务、三个角色隔离及用户可读展示。证据页检查Word分页、Excel工作表及单元格、高亮/页码、四次旋转、翻页、原件打开、关闭重开和网络请求数。普通页面不能泄露内部字段编码。既有业务任务保持原状态。

## 脱敏结果模板

本机新增报告建议命名为 `docs/qa-26/20261126-field-simulation-local26-results.md`。填写以下内容，并单独记录实际提交和26部署。

```text
验证范围 = LOCAL / 26 / FIELD
源码完整SHA =
本机工作树状态 =
26入口与发布目录 =
部署前JAR/index/资源SHA256 =
部署后JAR/index/资源SHA256 =
是否实际部署、时间及操作记录 =
活动任务预检结果与观察时间 =
各批历史ID、新ID及重跑理由 =
材料摘要清单引用及复核结果 =
逐文件状态/解析字符/分析字符/覆盖/耗时 =
B1—C5逐项状态、实际字段路径、证据位置及根因 =
八大项、证据页、历史、取消与角色隔离结果 =
前后端/组件/Chrome109实际命令、版本与结果 =
已解决问题、未解决问题及现场风险 =
甲方现场状态 = 待现场验收
```

只回传脱敏报告、状态统计和必要摘要。账号、口令、Token、连接串、原始正文与模型内容不得进入报告。云端收到报告后再按可复现失败处理下一轮。
