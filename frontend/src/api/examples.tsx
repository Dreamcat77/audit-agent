/**
 * API 使用示例
 * 展示如何在 React 组件中调用后端 API
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
} from '../api';

/**
 * 示例 1: F01 预算审批校验
 */
export function BudgetCheckExample() {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<any>(null);

  const handleCheck = async () => {
    setLoading(true);
    try {
      const data = await checkBudget('PR-2024-0183');
      setResult(data);
      console.log('预算审批结果:', data);
    } catch (error) {
      console.error('检查失败:', error);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <button onClick={handleCheck} disabled={loading}>
        {loading ? '检查中...' : '检查预算审批'}
      </button>
      {result && (
        <div>
          <p>审批结果: {result.checkResult}</p>
          <p>科目比对: {result.subjectCheckResult}</p>
          <p>金额比对: {result.amountCheckResult}</p>
        </div>
      )}
    </div>
  );
}

/**
 * 示例 2: F03 供应商黑名单检查
 */
export function BlacklistCheckExample() {
  const [loading, setLoading] = useState(false);

  const handleCheck = async () => {
    setLoading(true);
    try {
      const data = await checkBlacklist({
        supplierName: '华为技术有限公司',
        projectId: 'PRJ-001',
      });
      
      if (data.hit) {
        console.log('⚠️ 命中黑名单！', data.matchedRecords);
      } else {
        console.log('✅ 未命中黑名单');
      }
    } catch (error) {
      console.error('检查失败:', error);
    } finally {
      setLoading(false);
    }
  };

  return (
    <button onClick={handleCheck} disabled={loading}>
      {loading ? '检查中...' : '检查供应商黑名单'}
    </button>
  );
}

/**
 * 示例 3: F05 项目拆分风险检查
 */
export function SplitRiskCheckExample() {
  const handleCheck = async () => {
    try {
      const data = await checkSplitRisk({
        procurementIds: ['PR-2024-001', 'PR-2024-002', 'PR-2024-003'],
      });
      
      console.log('风险等级:', data.riskLevel);
      console.log('风险评分:', data.riskScore);
      console.log('拆分占比:', data.splitRatio);
    } catch (error) {
      console.error('检查失败:', error);
    }
  };

  return <button onClick={handleCheck}>检查项目拆分风险</button>;
}

/**
 * 示例 4: F07 合同结构对标
 */
export function ContractStructureCheckExample() {
  const handleCheck = async () => {
    try {
      const data = await checkContractStructure({
        contractId: 'CON-2024-001',
        tenderId: 'TEN-2024-001',
      });
      
      console.log('匹配率:', data.matchRate);
      console.log('缺失章节:', data.missingChapters);
      console.log('多出章节:', data.extraChapters);
    } catch (error) {
      console.error('检查失败:', error);
    }
  };

  return <button onClick={handleCheck}>检查合同结构</button>;
}

/**
 * 示例 5: F09 合同期限审查
 */
export function ContractPeriodCheckExample() {
  const handleCheck = async () => {
    try {
      const data = await checkContractPeriod({
        contractId: 'CON-2024-001',
        contractText: '本合同签订日期2024-01-01，有效期至2025-12-31...',
      });
      
      console.log('签订日期:', data.signDate);
      console.log('生效日期:', data.effectiveDate);
      console.log('风险等级:', data.riskLevel);
    } catch (error) {
      console.error('检查失败:', error);
    }
  };

  return <button onClick={handleCheck}>审查合同期限</button>;
}

/**
 * 示例 6: F11 围串标识别
 */
export function CollusionCheckExample() {
  const handleCheck = async () => {
    try {
      const data = await checkCollusion({
        projectId: 'PRJ-2024-001',
        bidDocuments: [
          {
            supplierName: '供应商A',
            bidPrice: 1000000,
            bidText: '投标文件内容...',
            uploadIp: '192.168.1.100',
          },
          {
            supplierName: '供应商B',
            bidPrice: 1020000,
            bidText: '投标文件内容...',
            uploadIp: '192.168.1.100',
          },
        ],
      });
      
      console.log('风险评分:', data.riskScore);
      console.log('风险等级:', data.riskLevel);
      console.log('文本相似度:', data.textSimilarityScore);
      console.log('报价异常度:', data.priceAnomalyScore);
      console.log('IP关联度:', data.ipCorrelationScore);
    } catch (error) {
      console.error('检查失败:', error);
    }
  };

  return <button onClick={handleCheck}>检查围串标风险</button>;
}

/**
 * 示例 7: F13 附件审查
 */
export function AttachmentCheckExample() {
  const handleCheck = async () => {
    try {
      const data = await checkAttachment({
        contractId: 'CON-2024-001',
        contractText: '本合同附件包括：附件A技术规格书，附件B报价清单...',
        attachments: ['附件A技术规格书', '附件C售后服务承诺'],
      });
      
      console.log('缺失附件:', data.missingAttachments);
      console.log('多出附件:', data.extraAttachments);
    } catch (error) {
      console.error('检查失败:', error);
    }
  };

  return <button onClick={handleCheck}>审查附件</button>;
}

/**
 * 示例 8: F15 条款风险提示
 */
export function ClauseRiskCheckExample() {
  const handleCheck = async () => {
    try {
      const data = await checkClauseRisk({
        contractId: 'CON-2024-001',
        contractText: '第一条 合同金额为100万元，付款方式为预付款40%...',
        tenderText: '第一条 合同金额为100万元，付款方式为预付款30%...',
      });
      
      console.log('偏离数量:', data.deviationCount);
      console.log('整体风险等级:', data.overallRiskLevel);
      data.deviations.forEach((d, i) => {
        console.log(`偏离${i + 1}:`, d.clauseType, d.riskLevel);
      });
    } catch (error) {
      console.error('检查失败:', error);
    }
  };

  return <button onClick={handleCheck}>检查条款风险</button>;
}
