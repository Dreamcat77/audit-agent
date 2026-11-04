import assert from 'node:assert/strict';
import test from 'node:test';
import { canCombineProcurementMaterials, mergeProcurementMaterialForm, procurementMaterialForm } from './procurementMaterialForm.ts';
import { readMaterialOperation, sameMaterialFile, materialOperationStorageKey } from './procurementMaterialOperation.ts';
import type { ProcurementSourceProject, ProcurementMaterialFields } from '../../../api/types';

test('different automatic amounts never silently choose whichever material arrived first', () => {
  const source = (id: string, amount: string): ProcurementSourceProject => ({id, name: '仿真项目', projectNo: 'P', updatedAt: null, category: null,
    fields: {projectName: '仿真项目', projectNo: 'P', departmentName: null, procurementType: null, budgetAmount: amount, subjectCode: null,
      subjectName: null, procurementMethod: null, requirementDescription: null, applicantName: null, evidence: {}, conflicts: [], aiModel: 'm', promptVersion: 'v'}});
  const a=source('a','120000.00'), b=source('b','80000.00');
  for (const [first, second] of [[a,b],[b,a]]) {
    const result=mergeProcurementMaterialForm(procurementMaterialForm(first), second);
    assert.equal(result.budgetAmount, '');assert.ok(result.materialConflicts?.includes('budgetAmount'));
    assert.equal(mergeProcurementMaterialForm(result, first).budgetAmount, '');
    assert.equal(mergeProcurementMaterialForm({...result, budgetAmount: '100000.00'}, first).budgetAmount, '100000.00');
  }
});

test('switching procurement material clears missing and conflicted suggestions without inventing applicant', () => {
  const fields: ProcurementMaterialFields = { projectName: '设备更新', projectNo: null, departmentName: '办公室',
    procurementType: '货物', budgetAmount: '120000.00', subjectCode: null, subjectName: null,
    procurementMethod: null, requirementDescription: null, applicantName: null,
    evidence: {}, conflicts: ['departmentName'], aiModel: 'model', promptVersion: 'v1' };
  const source: ProcurementSourceProject = { id: 'source', name: '设备更新', projectNo: null,
    category: '货物', updatedAt: null, fields };
  const form = procurementMaterialForm(source);
  assert.deepEqual(form.sourceMaterialIds, ['source']);
  assert.equal(form.budgetAmount, '120000.00');
  assert.equal(form.departmentName, '');
  assert.equal(form.subjectCode, '');
  assert.equal(form.applicantName, '');
  assert.equal(form.attachmentFile, null);
  assert.equal(procurementMaterialForm({ ...source, id: 'other', fields: null }).budgetAmount, '');
});

test('multiple parsed materials fill missing fields without replacing confirmed values', () => {
  const first: ProcurementSourceProject = { id: 'first', name: '设备更新', projectNo: null,
    category: '货物', updatedAt: null, fields: { projectName: '设备更新', projectNo: null,
      departmentName: null, procurementType: '货物', budgetAmount: '120000.00', subjectCode: null,
      subjectName: null, procurementMethod: null, requirementDescription: '服务器参数', applicantName: null,
      evidence: {}, conflicts: [], aiModel: 'model', promptVersion: 'v1' } };
  const second: ProcurementSourceProject = { ...first, id: 'second', fields: { ...first.fields!,
    projectName: '设备更新', departmentName: '信息中心', subjectCode: 'A1001', subjectName: '设备购置',
    procurementMethod: '公开招标', requirementDescription: '交付期限三十日' } };
  const current = { ...procurementMaterialForm(first), projectName: '人工确认名称' };

  const merged = mergeProcurementMaterialForm(current, second);

  assert.equal(merged.sourceProjectId, 'first');
  assert.deepEqual(merged.sourceMaterialIds, ['first', 'second']);
  assert.equal(merged.projectName, '人工确认名称');
  assert.equal(merged.departmentName, '信息中心');
  assert.equal(merged.subjectCode, 'A1001');
  assert.equal(merged.requirementDescription, '服务器参数\n交付期限三十日');
});

test('different project identities cannot be combined into one procurement demand', () => {
  const first = {id: 'first', name: '设备更新', projectNo: 'P-01', updatedAt: null, category: null, fields: null};
  assert.equal(canCombineProcurementMaterials(first, {...first, id: 'next', projectNo: 'P-02'}), false);
  assert.equal(canCombineProcurementMaterials({...first, projectNo: null},
    {...first, id: 'next', projectNo: null, name: '其他项目'}), false);
  assert.equal(canCombineProcurementMaterials(first, {...first, id: 'next', name: '其他材料', projectNo: 'P-01'}), true);
});

test('uncertain upload retains its operation identity and is scoped by actor', () => {
  const operation = { key: '00000000-0000-4000-8000-000000000001', name: '材料.docx', size: 100, lastModified: 12 };
  const storage = { getItem: (key: string) => key === materialOperationStorageKey('a') ? JSON.stringify(operation) : null };
  assert.deepEqual(readMaterialOperation(storage, 'a'), operation);
  assert.deepEqual(readMaterialOperation(storage, 'a'), operation);
  assert.equal(readMaterialOperation(storage, 'b'), null);
  assert.equal(sameMaterialFile(operation, operation), true);
  assert.equal(sameMaterialFile(operation, { ...operation, size: 101 }), false);
  assert.throws(() => readMaterialOperation({ getItem: () => '{broken' }, 'a'));
});
