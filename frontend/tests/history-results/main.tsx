import {useState} from 'react';
import {createRoot} from 'react-dom/client';
import {BudgetResultHistory} from '../../src/features/budget/BudgetResultHistory';
import {AutomaticCasePanel} from '../../src/features/procurement/AutomaticCasePanel';
import {ContractAnalysisWorkspace} from '../../src/features/contract/components/ContractAnalysisWorkspace';
import {AuthProvider} from '../../src/auth/AuthProvider';
import '../../src/styles/history-results.css';
import '../../src/styles/result-layout.css';
function App(){
  const [opened,setOpened]=useState('');
  const module=new URLSearchParams(location.search).get('module');
  if(module==='procurement')return <AutomaticCasePanel ownerId="history-test"/>;
  if(module==='contract')return <AuthProvider><ContractAnalysisWorkspace onOpenLegacy={()=>{}}/></AuthProvider>;
  return <main className="history-page"><header className="history-page-header"><h1 className="history-page-title">历史结果</h1></header>
    <BudgetResultHistory onOpen={entry=>setOpened(entry.batchId??entry.taskId??'')}/><output data-opened>{opened}</output></main>;
}
createRoot(document.getElementById('root')!).render(<App/>);
