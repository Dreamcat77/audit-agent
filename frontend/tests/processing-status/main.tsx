import React from 'react';
import {createRoot} from 'react-dom/client';
import {AutomaticCasePanel} from '../../src/features/procurement/AutomaticCasePanel';
import {BudgetFileResults} from '../../src/features/budget/BudgetFileResults';
import {ContractAnalysisWorkspace} from '../../src/features/contract/components/ContractAnalysisWorkspace';
import {AuthProvider} from '../../src/auth/AuthProvider';
import {ProcessingStatusCard} from '../../src/components/status/ProcessingStatusCard';
import '../../src/styles/result-layout.css';
import '../../src/styles/processing-status.css';
import './preview.css';

const mode=new URLSearchParams(location.search).get('module');
if(mode==='procurement')localStorage.setItem('procurement-auto-case-v1:status-test',JSON.stringify({caseId:'case-a'}));
function App(){
  if(mode==='procurement')return <AutomaticCasePanel ownerId="status-test" />;
  if(mode==='budget')return <BudgetFileResults batchId="batch-a" />;
  if(mode==='contract')return <AuthProvider><ContractAnalysisWorkspace onOpenLegacy={()=>{}}/></AuthProvider>;
  return <div className="status-gallery"><ProcessingStatusCard/><ProcessingStatusCard variant="compact" title="正在生成审查结果" description="处理完成后将自动展示结果。"/></div>;
}
createRoot(document.getElementById('root')!).render(<App/>);
