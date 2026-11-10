import {useCallback,useState} from 'react';
import {createRoot} from 'react-dom/client';
import {AnalysisEvidenceDialog} from '../../src/features/contract/components/AnalysisEvidenceDialog';
import type {SourceEvidence} from '../../src/features/contract/contractAnalysisEvidenceView';

const text='合同正文\n甲方应于验收合格后十日内支付尾款。\n'+'无关的其他条款。'.repeat(100)+'\n乙方逾期交付应承担违约责任。';
const annex='补充协议\n旧付款条款由本条替换，其他约定继续有效。';
function quote(fileId:string,text:string,excerpt:string):SourceEvidence{return {fileId,start:text.indexOf(excerpt),end:text.indexOf(excerpt)+excerpt.length,excerpt};}
const a=quote('contract',text,'甲方应于验收合格后十日内'),b=quote('contract',text,'验收合格后十日内支付尾款。');
const c=quote('contract',text,'乙方逾期交付应承担违约责任。'),d=quote('annex',annex,'旧付款条款由本条替换，其他约定继续有效。');
const mode=new URLSearchParams(location.search).get('case');
const originalMode=mode?.startsWith('original');
const officeMode=mode?.startsWith('office');
const officeText=mode?.includes('sheet')?'项目预算 | 2,200,000.00':'甲方应于验收合格后十日内支付尾款。';
const many=Array.from({length:9},(_,i)=>`第${i+1}项直接依据。`+'其他内容。'.repeat(80)).join('\n');
const pages=Array.from({length:12},(_,i)=>`第${i+1}页审查依据\n`+Array.from({length:45},(_,line)=>`条款${line+1}：付款及验收按本页约定执行。`).join('\n'));
const long=pages.join('\n'+'分页间隔。'.repeat(100)+'\n');
const single='本页原文：验收合格后十日内支付尾款。';
const large='无关的授权和通讯地址。'.repeat(35)+'我方的投标总价为：（大写）贰佰贰拾万元人民币/年。'+'其他一般约定。'.repeat(35);
const data=mode==='original-none'?[{...a,start:null,end:null}]:mode==='original-concise'?[{...quote('contract',large,large),pageNumber:2}]:originalMode?[{...a,pageNumber:mode==='original-page'?4:2},...(mode==='original-multi'?[{...c,pageNumber:9}]:[])]
  :mode==='long'?pages.map((page,i)=>({...quote('contract',long,page),pageNumber:i+1}))
  :mode==='single'?[{...quote('contract',single,single),pageNumber:1}]
  :mode==='many'?Array.from({length:9},(_,i)=>quote('contract',many,`第${i+1}项直接依据。`))
  :mode==='missing'?[{...a,start:null,end:null}]:mode==='bad'?[{...a,start:1,end:2},d]:[a,a,b,c,d,d];
declare global {interface Window {evidenceTestCalls:{fileId:string;start:number;size:number}[]}}
window.evidenceTestCalls=[];
function App(){
  const [open,setOpen]=useState(false);
  const load=useCallback(async(fileId:string,start:number,size:number)=>{
    window.evidenceTestCalls.push({fileId,start,size});
    await new Promise(r=>setTimeout(r,30));
    if(mode==='error'&&fileId==='contract')throw new Error('synthetic unavailable source');
    const value=officeMode?officeText:fileId==='contract'?(mode==='original-concise'?large:mode==='long'?long:mode==='single'?single:mode==='many'?many:text):annex;
    return {start,text:value.slice(start,start+size),totalChars:value.length};
  },[]);
  return <><button id="open" onClick={()=>setOpen(true)}>查看原文</button>{open&&<AnalysisEvidenceDialog evidence={officeMode?[{fileId:'contract',start:0,end:officeText.length,excerpt:mode==='office-mismatch'?'原文件不存在这句话':officeText,sourceLocator:mode?.includes('sheet')?'excel/sheet:1/row:3':undefined}]:data}
    documents={[{fileId:'contract',name:officeMode?(mode?.includes('sheet')?(mode.endsWith('xls')?'预算表.xls':'预算表.xlsx'):mode==='office-legacy'?'采购合同.doc':'采购合同.docx'):originalMode?'采购合同.pdf':'采购合同.docx'},{fileId:'annex',name:'付款补充协议.docx'}]}
    originalUrl={officeMode?(fileId=>`/synthetic-office/${fileId}/original`):undefined}
    loadText={load} focusText={mode==='original-concise'?'投标总价每年220万元，年度与整体计价范围不同':undefined} visualBase={officeMode?(fileId=>`/synthetic-office/${fileId}`):originalMode?(fileId=>`/synthetic-original/${fileId}`):undefined} onClose={()=>setOpen(false)}/>}</>;
}
createRoot(document.getElementById('root')!).render(<App/>);
