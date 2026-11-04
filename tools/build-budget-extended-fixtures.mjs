import {mkdirSync,writeFileSync,copyFileSync} from 'node:fs';
import path from 'node:path';

// Synthetic fixtures and expectations are independent of model responses.
const root=path.resolve(process.argv[2]??'.runtime/budget-extended/fixtures');
mkdirSync(root,{recursive:true});
const cases=[],expectations=[];
const prefix='【仿真测试材料，不对应真实单位、人员或业务】\n';
function add(label,documents,expectation,checks){
 const index=String(cases.length+1).padStart(2,'0');
 const files=documents.map((text,i)=>{
  const name=`${index}-${i+1}.txt`;
  writeFileSync(path.join(root,name),prefix+text,'utf8');return name;
 });
 cases.push({label,files,expectation});expectations.push({label,fileCount:files.length,checks});
}
const heading=(name,code='EXT-2026-01',year='2026',department='综合保障部')=>
 `项目名称：${name}\n项目编号：${code}\n预算年度：${year}\n申报部门：${department}\n`;
const line=(subject,declared,approved)=>`科目名称：${subject}\n申报金额：${declared}；批复金额：${approved}。\n`;
const exact=(project,subject,declaredAmount,approvalAmount,status)=>({kind:'exact',project,subject,declaredAmount,approvalAmount,status});
add('万元单位及小数精度',[heading('仿真机房改造')+line('机房工程','12.3456万元','12万元')],
 '申报123456元、批复120000元，差额3456元；不把万元当元。',
 [exact('仿真机房改造','机房工程',123456,120000,'OVER_APPROVAL')]);
add('财务大写含零角分',[heading('仿真档案迁移')+'档案搬迁服务科目，本次申请人民币壹万零叁元零伍分，批准人民币壹万元整。',
 ],'申报10003.05元、批复10000元，差额3.05元。',
 [exact('仿真档案迁移','档案搬迁服务',10003.05,10000,'OVER_APPROVAL')]);
add('分级大写复杂金额',[heading('仿真数据治理')+'数据整理服务科目申请人民币壹佰零贰万零叁佰零肆元伍角陆分；经研究批复人民币壹佰万元整。'],
 '申报1020304.56元、批复1000000元，差额20304.56元。',
 [exact('仿真数据治理','数据整理服务',1020304.56,1000000,'OVER_APPROVAL')]);
add('零额度不能当作缺失',[heading('仿真零额度项目')+line('咨询服务','1元','0元')],
 '批复为零是真实数值；申报1元应超过批复。',
 [exact('仿真零额度项目','咨询服务',1,0,'OVER_APPROVAL')]);
add('分币精确差额',[heading('仿真小额核对')+line('耗材购置','0.03元','0.02元')],
 '精确识别3分对2分，差额1分。',
 [exact('仿真小额核对','耗材购置',0.03,0.02,'OVER_APPROVAL')]);
add('单价数量总额的角色',[heading('仿真办公终端更新')+
 '办公终端购置科目采购20台，每台单价3500元，项目总控制额度200000元。本科目申报金额70000元，科目批复金额68000元。'],
 '只比较科目70000对68000；20、3500和200000不得冒充科目金额。',
 [exact('仿真办公终端更新','办公终端购置',70000,68000,'OVER_APPROVAL'),{kind:'forbidAmount',values:[20,3500,200000]}]);
add('只有项目控制总额',[heading('仿真总额区别')+'项目总预算控制额度300000元。档案设备购置科目本次申请56000元，尚未取得科目批复。'],
 '保留56000元申请，批复为空；300000不得作为科目批复。',
 [exact('仿真总额区别','档案设备购置',56000,null,'INSUFFICIENT'),{kind:'forbidAmount',values:[300000]}]);
add('同项目不同年度隔离',[
 heading('仿真年度隔离','EXT-YEAR-01','2026')+'设备购置科目本次申请90000元。',
 heading('仿真年度隔离','EXT-YEAR-01','2025')+'设备购置科目批复100000元。'],
 '2026申报不能套用2025批复，不得出现未超批复结论。',
 [{kind:'noWithin'},{kind:'noPairedAmounts'}]);
add('同名项目不同编号隔离',[
 heading('仿真设备更新','EXT-A-01')+'设备购置科目本次申请48000元。',
 heading('仿真设备更新','EXT-B-02')+'设备购置科目批复60000元。'],
 '名字相同但项目编号不同，不能跨项目比较。',
 [{kind:'noWithin'},{kind:'noPairedAmounts'}]);
add('同项目不同部门隔离',[
 heading('仿真部门隔离','EXT-DEPT-01','2026','信息部')+'软件服务科目申请45000元。',
 heading('仿真部门隔离','EXT-DEPT-01','2026','档案部')+'软件服务科目批复50000元。'],
 '部门不同不得混用额度；不得出现未超批复结论。',
 [{kind:'noWithin'},{kind:'noPairedAmounts'}]);
const duplicate=heading('仿真重复副本','EXT-DUP-01')+line('培训服务','8000元','10000元');
add('两份相同副本不能翻倍',[duplicate,duplicate],
 '相同副本仍是8000对10000，不得相加成16000或20000。',
 [exact('仿真重复副本','培训服务',8000,10000,'WITHIN_APPROVAL'),{kind:'forbidAmount',values:[16000,20000]}]);
add('两份不同批复保留冲突',[
 heading('仿真批复冲突','EXT-CONFLICT-01')+'会务服务科目本次申请50000元。',
 heading('仿真批复冲突','EXT-CONFLICT-01')+'会务服务科目原批复40000元。',
 heading('仿真批复冲突','EXT-CONFLICT-01')+'会务服务科目另有批复60000元；本材料没有说明替代原批复。'],
 '两版批复无法确定适用版本，应显示冲突，不得取较大额度判通过。',
 [{kind:'hasStatus',status:'CONFLICT'},{kind:'noWithin'}]);
add('完整科目比对错支漏项',[
 heading('仿真场馆维护','EXT-DIFF-01')+
 '本次申报的全部科目如下。\n'+line('照明维护','15000元','未提供')+'\n科目名称：绿化养护\n申报金额：3000元。',
 heading('仿真场馆维护','EXT-DIFF-01')+
 '本次批准的全部科目如下。\n科目名称：照明维护\n批复金额：12000元。\n科目名称：消防检验\n批复金额：5000元。'],
 '照明超支3000；绿化缺批复提示疑似错支；消防未申报提示疑似漏项，不能断言违法。',
 [exact('仿真场馆维护','照明维护',15000,12000,'OVER_APPROVAL'),
  {kind:'hasStatus',status:'UNAPPROVED_SUBJECT'},{kind:'hasStatus',status:'MISSING_DECLARATION'}]);
add('没有金额的预算说明',[heading('仿真未定价项目')+'设备购置科目拟购置终端，金额待询价后确定。'],
 '不编造金额，不宣称已通过金额核对。',
 [{kind:'noAmounts'},{kind:'noWithin'}]);
add('无关通知含编号日期及人数',['会议通知：2026年10月20日9时召开业务交流会，参会人数120人，会议室编号10000。材料不涉及预算申请或批复。'],
 '日期、人数、会议室编号不能当预算金额；没有预算结果属正常无结果。',
 [{kind:'noAmounts'},{kind:'noWithin'}]);
add('大小写金额不一致',[heading('仿真金额冲突')+
 '培训服务科目申请金额小写10000元，大写人民币贰万元整，两种金额未核对；科目批复金额15000元。'],
 '大小写冲突不能任取10000对15000宣称通过，应保留不确定性。',
 [{kind:'noWithin'},{kind:'hasUncertainty'}]);
add('金额字段包含负数冲减',[heading('仿真冲减调整')+line('设备维护','-5000元','10000元')],
 '负数调整不能去掉负号变成5000，不能直接判常规预算通过。',
 [{kind:'forbidDeclared',values:[5000]},{kind:'noWithin'}]);
add('同编号不同正文名称',[
 heading('仿真北区网络改造','EXT-NET-2026')+'网络设备科目申请84000元。',
 heading('北区网络升级工程','EXT-NET-2026')+'网络设备科目批复80000元。'],
 '同编号、正文名称不同，观察是否能关联84000对80000；不能凭相似名称串错项目。',
 [exact(null,'网络设备',84000,80000,'OVER_APPROVAL')]);
add('长材料首尾明细',[
 heading('仿真长文申报','EXT-LONG-01')+
 '本科目为档案数字化服务，本次申请48000元。\n'+
 Array.from({length:90},(_,i)=>`实施说明第${i+1}段：工作人员按规范整理档案目录，完成清点后移交，相关工作应保留记录。本段无新增预算明细。`).join('\n')+
 '\n本项目档案数字化服务科目最终批复额度为45000元。'],
 '长文首尾字段应关联为48000对45000，差额3000；不因分段漏末尾批复。',
 [exact('仿真长文申报','档案数字化服务',48000,45000,'OVER_APPROVAL')]);
const batchDocs=[];const batchChecks=[];
for(let i=1;i<=6;i++){
 const project='仿真多文件业务批次',subject=`第${i}项设备服务`;
 batchDocs.push(heading(project,'EXT-BATCH-12')+`科目名称：${subject}\n本次申请${i*10000+500}元。`);
 batchDocs.push(heading(project,'EXT-BATCH-12')+`科目名称：${subject}\n批准额度${i*10000}元。`);
 batchChecks.push(exact(project,subject,i*10000+500,i*10000,'OVER_APPROVAL'));
}
add('十二份材料同项目六科目',batchDocs,
 '12份材料归集为6组科目，每组申报比批复多500元，不漏科目、不跨科目串额。',batchChecks);
add('分行科目大小写混排',[heading('仿真综合维修','EXT-VERTICAL-02')+
 '科目名称：屋面维修\n申报金额：贰万伍仟元整\n批复金额：24000元\n\n科目名称：门窗维修\n申报金额：8000元\n批复金额：玖仟元整'],
 '不同科目不得串额，屋面超1000，门窗未超。',[
 exact('仿真综合维修','屋面维修',25000,24000,'OVER_APPROVAL'),exact('仿真综合维修','门窗维修',8000,9000,'WITHIN_APPROVAL')]);
add('全角数字与中文千分隔',[heading('仿真全角金额')+line('设备安装','１２，３４５．６７元','１２，０００．００元')],
 '全角金额12345.67对12000，精确差345.67元。',[exact('仿真全角金额','设备安装',12345.67,12000,'OVER_APPROVAL')]);
add('千元计量与小数',[heading('仿真单位换算')+'科目名称：排水维护\n申报金额（千元）：12.345\n批复金额（元）：12000'],
 '千元列换算12345对12000，不能把千元和元混用。',[exact('仿真单位换算','排水维护',12345,12000,'OVER_APPROVAL')]);
add('空白批复占位不等于零',[heading('仿真未批复')+line('安全巡检','6000元','未填写')],
 '保留6000元，缺少批复，不能按零批复算超支。',[exact('仿真未批复','安全巡检',6000,null,'INSUFFICIENT')]);
add('多项目同一文档隔离',[
 '项目名称：仿真东楼；项目编号：MULTI-A；科目名称：装修；申报金额：12000元；批复金额：10000元。\n'+
 '项目名称：仿真西楼；项目编号：MULTI-B；科目名称：装修；申报金额：5000元；批复金额：8000元。'],
 '单文档含两项目，各自比较，不能跨项目加总。',[
 exact('仿真东楼','装修',12000,10000,'OVER_APPROVAL'),exact('仿真西楼','装修',5000,8000,'WITHIN_APPROVAL')]);
add('同编号别名仍隔离年度',[
 heading('仿真东区改造','ALIAS-YEAR','2026')+'网络服务科目申请45000元。',
 heading('东区信息提升工程','ALIAS-YEAR','2025')+'网络服务科目批复60000元。'],
 '编号相同不能跨年度取额度。',[{kind:'noWithin'},{kind:'noPairedAmounts'}]);
add('同编号别名仍隔离部门',[
 heading('仿真综合改造','ALIAS-DEPT','2026','后勤部')+'维修服务科目申请45000元。',
 heading('综合提升工程','ALIAS-DEPT','2026','信息部')+'维修服务科目批复60000元。'],
 '编号相同但部门不同，不能强配额度。',[{kind:'noWithin'},{kind:'noPairedAmounts'}]);
add('含单价税率比例与人数的说明',[heading('仿真安保培训')+
 '安保培训科目拟安排30人，服务单价300元，税率6%，本次拟申请经费人民币玖仟元整。经核定该科目批准捌仟伍佰元整，培训地点为301室。'],
 '只比较9000对8500，不能把税率人数单价房间号当金额。',[
 exact('仿真安保培训','安保培训科目',9000,8500,'OVER_APPROVAL'),{kind:'forbidAmount',values:[30,300,6,301]}]);
add('金额范围不能任选上限',[heading('仿真估算区间')+'科目名称：设施修缮\n申报金额：10000至15000元\n批复金额：12000元'],
 '范围金额无法确定具体申报，不可任取上限或下限。',[{kind:'noWithin'},{kind:'forbidDeclared',values:[10000,15000]}]);
add('超过两位小数不偷偷四舍五入',[heading('仿真分币精度')+line('档案服务','100.005元','100元')],
 '不能四舍五入为100.01或截断为100。',[{kind:'noWithin'},{kind:'forbidDeclared',values:[100,100.01]}]);
add('批复文件在前申报在后',[
 heading('仿真顺序无关','EXT-ORDER')+'科目名称：消防维保\n批复金额：叁万陆仟元整',
 heading('仿真顺序无关','EXT-ORDER')+'科目名称：消防维保\n申报金额：38000元'],
 '不依赖上传顺序，比较38000对36000。',[exact('仿真顺序无关','消防维保',38000,36000,'OVER_APPROVAL')]);
add('批复文书式自然段',[heading('仿真文书材料')+
 '经研究，同意本单位提出的库房修缮事项。库房修缮科目本次报送经费为人民币伍万贰仟元整，现核准该科目经费人民币伍万元整。请按规定组织实施，不得挤占其他科目资金。'],
 '非表格的文书也应提取52000对50000，差额2000。',[exact('仿真文书材料','库房修缮',52000,50000,'OVER_APPROVAL')]);
writeFileSync(path.join(root,'suite.json'),JSON.stringify({label:'SYNTHETIC_BUDGET_ACCEPTANCE',cases},null,2));
writeFileSync(path.join(root,'expected.json'),JSON.stringify(expectations,null,2));
const formats=[['旧版Word复测','sample.doc'],['Word表格复测','sample-table.docx'],
 ['旧版Excel复测','sample.xls'],['Excel工作簿复测','sample.xlsx'],['原生PDF复测','sample.pdf'],
 ['纯扫描PDF复测','sample-scan.pdf'],['混合PDF复测','sample-mixed.pdf'],['PNG扫描复测','sample.png'],
 ['JPEG扫描复测','sample.jpg'],['RTF复测','sample.rtf'],['演示文稿复测','sample.pptx'],
 ['UTF16文本复测','sample-utf16.txt'],['损坏DOCX拒绝','expected-corrupt.docx']];
for(const [,name]of formats)copyFileSync(path.join('.runtime/office-fixtures',name),path.join(root,name));
writeFileSync(path.join(root,'formats.json'),JSON.stringify({label:'SYNTHETIC_BUDGET_ACCEPTANCE',
 cases:formats.map(([label,file])=>({label,files:[file],expectation:file==='expected-corrupt.docx'?
  '明确拒绝损坏容器，不发布成功结果':
  '材料为既有仿真样本，核对31200元申报；原文有无科目批复须逐份核对，项目控制总额不能代替科目批复。'}))},null,2));
console.log(JSON.stringify({cases:cases.length,files:cases.flatMap(c=>c.files).length,formatCases:formats.length,root}));
