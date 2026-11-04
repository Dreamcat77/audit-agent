import { createRoot } from 'react-dom/client';
import { useState } from 'react';
import { AuthGate, AuthProvider, useAuth } from './auth/AuthProvider';
import { AutomaticCasePanel } from './features/procurement/AutomaticCasePanel';
import './styles/index.css';
import { installPilotUuid } from './pilot-uuid.mjs';

installPilotUuid(window.crypto);

function ProcurementPilot() {
  const { user, logout } = useAuth();
  const [error, setError] = useState('');
  if (!user) return null;
  return <main className="min-h-screen bg-slate-50 text-slate-900">
    <header className="flex flex-wrap items-center justify-between gap-4 border-b bg-white px-6 py-4">
      <div><h1 className="text-xl font-semibold">采购自动审查 · 独立内网测试版</h1>
        <p className="mt-1 text-sm text-slate-600">自动执行智能体预审、询比价、F10招标文件合规。分析完成不等于全部合规。</p></div>
      <button type="button" className="rounded border px-4 py-2 text-sm" onClick={() => {
        setError(''); void logout().catch(() => setError('退出失败，请重试。'));
      }}>退出登录</button>
    </header>
    <div className="mx-auto max-w-7xl p-6">
      {error && <p role="alert" className="mb-4 text-red-700">{error}</p>}
      <AutomaticCasePanel ownerId={user.userId} />
    </div>
  </main>;
}

createRoot(document.getElementById('root')!).render(
  <AuthProvider><AuthGate><ProcurementPilot /></AuthGate></AuthProvider>,
);
