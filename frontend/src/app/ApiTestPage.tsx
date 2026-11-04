/**
 * API 测试页面
 * 用于验证前后端连通性和所有 API 接口
 */

import { useState } from 'react';
import {
  checkBudget,
  checkBlacklist,
  checkSplitRisk,
  checkContractStructure,
  checkContractPeriod,
  checkCollusion,
  checkAttachment,
  checkClauseRisk,
  healthCheck,
} from '../api';

export function ApiTestPage() {
  const [results, setResults] = useState<Record<string, any>>({});
  const [loading, setLoading] = useState<Record<string, boolean>>({});

  const runTest = async (name: string, fn: () => Promise<any>) => {
    setLoading(prev => ({ ...prev, [name]: true }));
    try {
      const result = await fn();
      setResults(prev => ({ ...prev, [name]: { success: true, data: result } }));
      console.log(`✅ ${name} 测试通过:`, result);
    } catch (error: any) {
      setResults(prev => ({ ...prev, [name]: { success: false, error: error.message } }));
      console.error(`❌ ${name} 测试失败:`, error);
    } finally {
      setLoading(prev => ({ ...prev, [name]: false }));
    }
  };

  const runAllTests = async () => {
    // F01 预算审批
    await runTest('F01-预算审批', () => checkBudget('00000000-0000-0000-0000-000000000101'));

    // F03 黑名单检查
    await runTest('F03-黑名单', () => checkBlacklist({ supplierName: '华为技术有限公司' }));

    // F05 拆分风险
    await runTest('F05-拆分风险', () => checkSplitRisk({
      procurementIds: ['PROC-001', 'PROC-002']
    }));

    // F07 合同结构对标
    await runTest('F07-结构对标', () => checkContractStructure({
      contractId: 'CON-2024-001',
      tenderId: 'TEN-2024-001'
    }));

    // F09 合同期限审查
    await runTest('F09-期限审查', () => checkContractPeriod({
      contractId: 'CON-2024-001',
      contractText: '本合同签订日期2024-01-01，有效期至2025-12-31，交付日期2024-06-30，验收日期2024-07-15。如合同终止，需提前30天通知。'
    }));

    // F11 围串标识别
    await runTest('F11-围串标', () => checkCollusion({
      tenderId: 'TEN-2024-001',
      bidDocuments: [
        {
          supplierName: '供应商A',
          bidPrice: 1000000,
          bidText: '投标文件内容A，包含技术方案和商务报价...',
          uploadIp: '192.168.1.100'
        },
        {
          supplierName: '供应商B',
          bidPrice: 1020000,
          bidText: '投标文件内容B，包含技术方案和商务报价...',
          uploadIp: '192.168.1.100'
        }
      ]
    }));

    // F13 附件审查
    await runTest('F13-附件审查', () => checkAttachment({
      contractId: 'CON-2024-001',
      contractText: '本合同附件包括：附件A技术规格书，附件B报价清单，附件C售后服务承诺。',
      attachments: ['附件A技术规格书', '附件B报价清单']
    }));

    // F15 条款风险提示
    await runTest('F15-条款风险', () => checkClauseRisk({
      contractId: 'CON-2024-001',
      contractText: '第一条 合同金额为100万元，付款方式为预付款40%，质保期1年。',
      tenderText: '第一条 合同金额为100万元，付款方式为预付款30%，质保期2年。'
    }));
  };

  const testHealth = async () => {
    await runTest('健康检查', healthCheck);
  };

  return (
    <div className="p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold text-slate-900">API 接口测试</h2>
          <p className="text-sm text-muted-foreground mt-1">验证前后端连通性和所有功能接口</p>
        </div>
        <div className="flex gap-2">
          <button
            onClick={testHealth}
            className="px-4 py-2 rounded bg-blue-50 text-blue-700 border border-blue-200 hover:bg-blue-100 transition-colors text-sm"
          >
            测试健康检查
          </button>
          <button
            onClick={runAllTests}
            className="px-4 py-2 rounded bg-emerald-50 text-emerald-700 border border-emerald-200 hover:bg-emerald-100 transition-colors text-sm"
          >
            运行全部测试
          </button>
        </div>
      </div>

      <div className="space-y-3">
        {Object.entries(results).map(([name, result]) => (
          <div
            key={name}
            className={`p-4 rounded-lg border ${
              result.success
                ? 'bg-emerald-50 border-emerald-200'
                : 'bg-red-50 border-red-200'
            }`}
          >
            <div className="flex items-center justify-between mb-2">
              <span className="text-sm font-medium text-slate-900">{name}</span>
              <span className={`text-xs px-2 py-0.5 rounded ${
                result.success
                  ? 'bg-emerald-50 text-emerald-700'
                  : 'bg-red-50 text-red-700'
              }`}>
                {result.success ? '✅ 成功' : '❌ 失败'}
              </span>
            </div>
            {result.success ? (
              <pre className="text-xs text-muted-foreground bg-slate-100 p-3 rounded overflow-x-auto">
                {JSON.stringify(result.data, null, 2)}
              </pre>
            ) : (
              <p className="text-xs text-red-700">{result.error}</p>
            )}
          </div>
        ))}
      </div>

      {Object.keys(loading).some(k => loading[k]) && (
        <div className="text-center text-sm text-blue-700">
          测试执行中...
        </div>
      )}

      <div className="bg-blue-50 border border-blue-200 rounded-lg p-4">
        <h3 className="text-sm font-medium text-blue-700 mb-2">使用说明</h3>
        <ul className="text-xs text-muted-foreground space-y-1">
          <li>1. 确保后端服务已启动（http://localhost:8080）</li>
          <li>2. 确保数据库已建表并有测试数据</li>
          <li>3. 点击"运行全部测试"验证所有接口</li>
          <li>4. 查看控制台和页面输出确认结果</li>
          <li>5. 后端地址通过 VITE_API_BASE_URL / VITE_API_PROXY_TARGET 配置</li>
        </ul>
      </div>
    </div>
  );
}
