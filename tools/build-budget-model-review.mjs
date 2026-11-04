import {readFileSync,writeFileSync,mkdirSync} from 'node:fs';
import path from 'node:path';
const args=process.argv.slice(2),checksIndex=args.indexOf('--checks');
const checks=checksIndex<0?null:JSON.parse(readFileSync(args.splice(checksIndex,2)[1],'utf8'));
const [destination,...sources]=args;
if(!destination||!sources.length)throw Error('OUTPUT_DIRECTORY RESULTS_JSON...');
const cases=[...new Map(sources.flatMap(source=>JSON.parse(readFileSync(source,'utf8'))).map(c=>[c.label,c])).values()];
const esc=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const names={projectName:'项目名称',projectCode:'项目编号',subjectName:'预算科目',subjectCode:'科目编码',budgetYear:'预算年度',departmentName:'申报部门',supplierName:'供应商',declaredAmount:'申报金额',approvalAmount:'批复金额',unknown:'其他内容'};
const statuses={OVER_APPROVAL:'申报超过批复',WITHIN_APPROVAL:'申报未超过批复',INSUFFICIENT:'现有信息尚不能完整比对',CONFLICT:'材料版本或金额存在冲突',INVALID_AMOUNT:'金额需要核对',UNAPPROVED_SUBJECT:'疑似错支',MISSING_DECLARATION:'疑似漏项'};
const money=value=>value==null?'未能确认':Number(value).toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})+' 元';
let fileCount=0,modelCount=0,ocrCount=0;
const body=cases.map((scenario,index)=>`<section id="case-${index}"><h2>${index+1}　${esc(scenario.label)}</h2>
<p class="expectation">核对目标：${esc(scenario.expectation)}</p>${checks?.records?.some(r=>r.label===scenario.label)?
 `<p class="${checks.records.find(r=>r.label===scenario.label).passed?'meta':'warning'}">${checks.records.find(r=>r.label===scenario.label).passed?
 '本组已达到预设核对目标，仅限本次测试材料。':'本组尚未达到预设核对目标，请重点审核下方实际结果。'}</p>`:''}${scenario.files.map(file=>{
 fileCount++;modelCount+=(file.extraction?.acknowledgedModelResults??[]).length;if(file.readMethod==='OFFICE_TEXT_OCR')ocrCount++;
 const result=file.result,lines=result?.lines??[];
 const raw=file.sourceText?.map(s=>s.text).join('\n\n')??'';
 const modelFacts=(file.extraction?.acknowledgedModelResults??[]).flatMap(r=>r.rows.flatMap(row=>row.facts));
 const issues=file.extraction?.issues??[];
 return `<article><h3>${esc(file.fileName)}</h3><p class="meta">${file.readMethod==='OFFICE_TEXT_OCR'?'已实际执行OCR':file.sourceText?'已读取文件原生文字':'本份未取得可用原文'}${file.sourceTextReused?file.modelResultReused?'；本次复核复用本轮读取的原文':'；本次复核复用本轮读取的原文，并重新调用模型':''}${file.modelResultReused?'；使用本轮已保存的真实模型响应重新核对':''}${file.elapsedMillis!=null?`；本份读取与提取用时 ${(file.elapsedMillis/1000).toFixed(1)} 秒`:''}</p>
 ${file.failureCode?`<p class="warning">本份未取得有效结果，原因码：${esc(file.failureCode)}。不能视为验证通过。</p>`:''}
 <p class="summary">${esc(result?.summary)}</p>
 ${lines.map(line=>`<div class="finding ${['OVER_APPROVAL','UNAPPROVED_SUBJECT','MISSING_DECLARATION'].includes(line.status)?'risk':''}">
 <h4>${esc(line.project??'项目尚未确认')}　${esc(line.subject??line.subjectCode??'科目尚未确认')}</h4>
 <p class="amounts">申报：${esc(money(line.declaredAmount))}　　批复：${esc(money(line.approvalAmount))}</p>
 <p class="state">${esc(statuses[line.status]??line.status)}</p><p>${esc(line.opinion)}</p></div>`).join('')}
 ${issues.length?`<p class="warning">本份有 ${issues.length} 类未核实信息，未参与完整性结论。涉及：${esc([...new Set(issues.map(i=>names[i.field]??i.field))].join('、'))}。</p>`:''}
 <details><summary>查看本份读取原文</summary><pre>${esc(raw||'未取得可用文本')}</pre></details>
 ${modelFacts.length?`<details><summary>查看真实模型提取内容</summary><ul>${modelFacts.map(f=>`<li><strong>${esc(names[f.field]??f.field)}</strong>：${esc(f.value)}<p class="quote">原文摘录：${esc(f.excerpt)}</p></li>`).join('')}</ul></details>`:
 `<p class="meta">${(file.extraction?.acknowledgedModelResults??[]).length?'模型未提取到预算字段。':'本份为明确字段或表格，由原生读取直接提取；未调用模型。'}</p>`}
 </article>`;
}).join('')}</section>`).join('');
const html=`<!doctype html><html lang="zh-CN"><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>预算文件实际处理结果</title>
<style>body{margin:0;background:#f5f6f8;color:#232b35;font:16px/1.8 "Microsoft YaHei","Noto Sans CJK SC",Arial,sans-serif}main{max-width:1080px;margin:auto;padding:36px 28px 80px}h1{font-size:28px;margin:0 0 14px}h2{font-size:22px;margin:0 0 8px}h3{font-size:18px;margin:0}h4{font-size:17px;margin:0 0 8px}p{margin:8px 0}.lead{font-size:18px}.meta{font-size:14px;color:#596572}nav{display:flex;flex-wrap:wrap;gap:8px 20px;padding:20px 0}a{color:#245b86;text-decoration:underline}section{padding:26px 0;border-top:1px solid #cdd5de}article{background:white;padding:22px 26px;margin:18px 0}.expectation{color:#596572}.finding{padding:16px 0;border-top:1px solid #e3e7ed}.finding.risk h4,.finding.risk .state{color:#a52b26}.amounts{font-weight:600}.state{font-weight:600}.summary{margin:16px 0}.warning{color:#805519}details{margin:12px 0}summary{cursor:pointer;color:#245b86}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:15px/1.9 "Microsoft YaHei",sans-serif;background:#f7f8fa;padding:18px}li{margin:10px 0}.quote{font-size:14px;color:#596572;margin:2px 0}footer{padding-top:24px;border-top:1px solid #cdd5de;font-size:14px;color:#596572}@media(max-width:640px){main{padding:22px 14px}article{padding:16px}nav{display:block}nav a{display:block}}</style>
<style>article{border:1px solid #dbe2ea;border-radius:16px}article>h3{padding-bottom:12px;border-bottom:1px solid #e2e8f0}</style>
<main><h1>预算文件实际处理结果</h1><p class="lead">请重点审核：材料内容是否被正确理解，金额是否准确，核对意见是否对工作人员有帮助。</p>
<p>本页展示 ${cases.length} 组场景、${fileCount} 份次仿真材料的实际结果。其中 ${modelCount} 份次经过真实模型提取，${ocrCount} 份次实际执行了 OCR。金额比较与差额由程序精确计算。</p>
<p class="meta">环境：26 测试服务器上的隔离候选代码与其当前配置的真实模型；不是甲方离线模型验收。原文均为仿真材料。本页直接读取实际结果文件生成，未人工修改模型提取值或最终核对意见。运行系统和业务数据库未被本次隔离验收修改。</p>
<p class="warning">${checks?`本次 ${checks.cases} 组测试核对中，${checks.passed} 组达到目标，${checks.failed} 组尚未达到目标。不能将程序返回成功等同于业务结果正确。`:
 '请同时审核未识别完整的样本：扫描表格仍有一项名称错位，部分 PDF 明细未能核实；重复工作表和 OCR 编号差异也会影响归集。下方保留实际结果，没有把这些情况改成“全部通过”。'}</p>
<nav>${cases.map((c,i)=>`<a href="#case-${i}">${esc(c.label)}</a>`).join('')}</nav>${body}
<footer>阅读方式：先看金额与核对意见；需要核实依据时，展开“读取原文”和“真实模型提取内容”。没有比对结论不等于没有问题。</footer></main></html>`;
mkdirSync(destination,{recursive:true});writeFileSync(path.join(destination,'预算实测结果.html'),html);
writeFileSync(path.join(destination,'actual-results.json'),JSON.stringify(cases,null,2));
console.log(JSON.stringify({cases:cases.length,fileCount,modelCount,ocrCount,report:path.resolve(destination,'预算实测结果.html')}));
