import { useRef, useState, useSyncExternalStore } from 'react';
import { Copy, Check } from 'lucide-react';
import { writeClipboardText } from '../../utils/clipboard';
let copySequence=0;
let copiedOwner:symbol|null=null;
const listeners=new Set<()=>void>();
function publishCopy(owner:symbol|null){copiedOwner=owner;for(const listener of listeners)listener();}
function subscribeCopy(listener:()=>void){listeners.add(listener);return ()=>{listeners.delete(listener);};}
export async function copyResultText(text:string):Promise<void> {
  await writeClipboardText(text);
}
export function ResultCopyButton(){
  const owner=useRef(Symbol('result-copy'));
  const latestOwner=useSyncExternalStore(subscribeCopy,()=>copiedOwner,()=>null);
  const [state,setState]=useState<'idle'|'ok'|'error'>('idle');
  const copied=state==='ok'&&latestOwner===owner.current;
  return <span data-no-copy className="inline-flex items-center gap-2"><button type="button" aria-label="复制本块结果" onClick={async event=>{
    const block=event.currentTarget.closest('[data-copy-result]');if(!block)return;
    const sequence=++copySequence;publishCopy(null);setState('idle');
    const clone=block.cloneNode(true) as HTMLElement;clone.querySelectorAll('button,[data-no-copy],input,select,textarea').forEach(node=>node.remove());
    const body=Array.from(clone.querySelectorAll<HTMLElement>('[data-copy-content]')).filter(node=>node.closest('[data-copy-result]')===clone);
    if(body.length){const content=document.createDocumentFragment();for(const node of body)content.append(node);clone.replaceChildren(content);}
    clone.querySelectorAll('[data-copy-heading]').forEach(node=>node.remove());
    clone.style.position='fixed';clone.style.left='-9999px';clone.style.width='800px';document.body.append(clone);
    const text=clone.innerText;clone.remove();
    try{await copyResultText(text);if(sequence===copySequence){setState('ok');publishCopy(owner.current);}}catch{if(sequence===copySequence)setState('error');}
  }} className="inline-flex shrink-0 items-center gap-1 rounded border border-slate-200 bg-white px-2 py-1 text-xs font-normal text-slate-600">{copied?<Check size={12}/>:<Copy size={12}/>}{copied?'已复制':'复制'}</button>{state==='error'&&<span role="status" className="text-xs text-amber-700">复制未成功，请选中文字复制</span>}</span>;
}
