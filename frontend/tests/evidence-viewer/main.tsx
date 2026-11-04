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
const many=Array.from({length:9},(_,i)=>`第${i+1}项直接依据。`+'其他内容。'.repeat(80)).join('\n');
const data=mode==='many'?Array.from({length:9},(_,i)=>quote('contract',many,`第${i+1}项直接依据。`))
  :mode==='missing'?[{...a,start:null,end:null}]:mode==='bad'?[{...a,start:1,end:2},d]:[a,a,b,c,d,d];
declare global {interface Window {evidenceTestCalls:{fileId:string;start:number;size:number}[]}}
window.evidenceTestCalls=[];
function App(){
  const [open,setOpen]=useState(false);
  const load=useCallback(async(fileId:string,start:number,size:number)=>{
    window.evidenceTestCalls.push({fileId,start,size});
    await new Promise(r=>setTimeout(r,30));
    if(mode==='error'&&fileId==='contract')throw new Error('synthetic unavailable source');
    const value=fileId==='contract'?(mode==='many'?many:text):annex;
    return {start,text:value.slice(start,start+size),totalChars:value.length};
  },[]);
  return <><button id="open" onClick={()=>setOpen(true)}>查看原文</button>{open&&<AnalysisEvidenceDialog evidence={data}
    documents={[{fileId:'contract',name:'采购合同.docx'},{fileId:'annex',name:'付款补充协议.docx'}]}
    loadText={load} onClose={()=>setOpen(false)}/>}</>;
}
createRoot(document.getElementById('root')!).render(<App/>);
