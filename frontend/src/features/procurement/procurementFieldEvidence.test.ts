import assert from 'node:assert/strict';
import { fieldEvidence } from './procurementFieldEvidence.ts';

const facts = [
  { field: 'projectName', value: '市政设备采购项目', excerpt: '项目名称：市政设备采购项目', locator: 'p1' },
  { field: 'projectName', value: '办公终端更新项目', excerpt: '项目名称：办公终端更新项目', locator: 'p2' },
  { field: 'projectName', value: '市政设备采购项目', excerpt: '本项目名称：市政设备采购项目', locator: 'p3' },
  { field: 'projectNo', value: 'CG-001', excerpt: '政府采购编号：CG-001', locator: 'p4' },
];

const names = fieldEvidence(facts, 'projectName');
assert.deepEqual(names.map(item => item.value), ['市政设备采购项目', '办公终端更新项目']);
assert.deepEqual(names[0]?.excerpts, ['项目名称：市政设备采购项目', '本项目名称：市政设备采购项目']);
assert.deepEqual(fieldEvidence(facts, 'projectNo').map(item => item.value), ['CG-001']);
