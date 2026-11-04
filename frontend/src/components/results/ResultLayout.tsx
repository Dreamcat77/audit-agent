import {useEffect,useState,type ReactNode} from 'react';
import {ChevronDown,FileText} from 'lucide-react';

export function ResultProjectHeader({name,kind,metadata,children,actions}:{
  name:string;kind:string;metadata?:ReactNode;children?:ReactNode;actions?:ReactNode;
}){
  return <header className="result-project-header">
    <div className="result-project-main"><span className="result-project-symbol" aria-hidden="true"><FileText size={23}/></span>
      <div className="result-project-heading"><p className="result-eyebrow">{kind} · 项目名称</p><h2 data-result-project-name>{name}</h2>{metadata&&<div className="result-project-metadata">{metadata}</div>}</div>
      {actions&&<div className="result-project-actions">{actions}</div>}
    </div>{children&&<div className="result-project-facts">{children}</div>}
  </header>;
}

export function ResultSection({title,number,children,actions,tone='default',label,collapsible=true}:{
  title:string;number?:string;children:ReactNode;actions?:ReactNode;tone?:'default'|'attention'|'clear';label?:string;collapsible?:boolean;
}){
  const [expanded,setExpanded]=useState(tone==='attention');
  useEffect(()=>setExpanded(tone==='attention'),[tone,title]);
  const heading=<>{number&&<span className="result-section-number" aria-hidden="true">{number}</span>}<span>{title}</span>{tone==='clear'&&<span className="result-state-clear">未见异常</span>}{collapsible&&<ChevronDown size={16} className={expanded?'result-chevron-expanded':''} aria-hidden="true"/>}</>;
  return <section aria-label={label??title} className={`result-section result-section-${tone}`}>
    <header className="result-section-heading"><h3 className="result-section-title">{collapsible?<button type="button" className="result-section-toggle" aria-expanded={expanded} onClick={()=>setExpanded(value=>!value)}>{heading}</button>:heading}</h3>{actions&&<div className="result-section-actions">{actions}</div>}</header>
    <div className="result-section-body" hidden={collapsible&&!expanded}>{children}</div>
  </section>;
}

/** Neutral means information/reference only, never a successful risk check. */
export function ResultItem({title,children,tone='default',label}:{title:ReactNode;children:ReactNode;tone?:'default'|'attention'|'clear';label?:string}){
  return <article className={`result-item result-item-${tone}`}>
    <header className="result-item-heading"><div className="result-item-title">{title}</div>{label&&<span className={`result-item-label${tone==='clear'?' result-state-clear':''}`}>{label}</span>}</header>
    <div className="result-item-body">{children}</div>
  </article>;
}

export function ResultEmpty({children='材料不足，未生成'}:{children?:ReactNode}){
  return <div className="result-empty"><span aria-hidden="true" className="result-empty-mark">—</span><p>{children}</p></div>;
}
