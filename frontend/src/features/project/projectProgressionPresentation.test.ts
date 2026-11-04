import assert from "node:assert/strict";
import test from "node:test";
import { projectFileProgressionPresentation } from "./projectProgressionPresentation.ts";

test("progression presentation follows server actions instead of inferring from missing candidates", () => {
  const role = projectFileProgressionPresentation({
    stage: "NEEDS_ROLE_CONFIRMATION", blocker: "NEEDS_ROLE_CONFIRMATION",
    availableActions: ["CONFIRM_ROLE"], candidateId: null, contractId: null,
  });
  assert.equal(role.statusLabel, "系统正在识别材料类型");
  assert.deepEqual(role.actions, [{ key: "CONFIRM_ROLE", label: "系统识别材料类型" }]);

  const identity = projectFileProgressionPresentation({
    stage: "NEEDS_IDENTITY_CONFIRMATION", blocker: "NEEDS_IDENTITY_CONFIRMATION",
    availableActions: ["CONFIRM_PROJECT_IDENTITY"], candidateId: null, contractId: null,
  });
  assert.equal(identity.statusLabel, "系统正在判断材料关联");
  assert.deepEqual(identity.actions, [
    { key: "CONFIRM_PROJECT_IDENTITY", label: "系统判断材料关联" },
  ]);
});

test("fact extraction pending is presented as an explicit resumable preparation stage", () => {
  const view = projectFileProgressionPresentation({
    stage: "IDENTITY_CONFIRMED", blocker: "FACT_EXTRACTION_PENDING",
    availableActions: ["RESUME_FACT_EXTRACTION"], candidateId: null, contractId: null,
    recoveryInputFingerprint: "c".repeat(64),
  });

  assert.equal(view.statusLabel, "关键事实提取尚未完成");
  assert.deepEqual(view.actions, [{ key: "RESUME_FACT_EXTRACTION", label: "继续提取关键事实" }]);
});

test("candidate, formal contract and review stages expose the five explicit CTA labels", () => {
  const cases = [
    ["CANDIDATE_NEEDS_CONFIRMATION", "CONFIRM_CONTRACT_CANDIDATE", "合同信息已提取，系统正在整理", "系统整理合同信息"],
    ["CANDIDATE_READY", "PROMOTE_CONTRACT", "合同信息已整理，正在生成正式合同", "系统生成正式合同"],
    ["REVIEW_READY", "START_REVIEW", "正式合同已生成，可开始审核", "开始合同审核"],
  ] as const;
  for (const [stage, action, statusLabel, actionLabel] of cases) {
    const view = projectFileProgressionPresentation({
      stage, blocker: null, availableActions: [action], candidateId: "candidate", contractId: null,
    });
    assert.equal(view.statusLabel, statusLabel);
    assert.deepEqual(view.actions, [{ key: action, label: actionLabel }]);
  }
});

test("parsed and waiting stages stay visible without inventing commands", () => {
  assert.deepEqual(projectFileProgressionPresentation({
    stage: "PARSED", blocker: "ROLE_ASSESSMENT_PENDING", availableActions: [],
    candidateId: null, contractId: null,
  }), { statusLabel: "解析完成，系统正在识别材料类型", actions: [] });
  assert.deepEqual(projectFileProgressionPresentation({
    stage: "ROLE_CONFIRMED", blocker: "IDENTITY_ASSESSMENT_PENDING", availableActions: [],
    candidateId: null, contractId: null,
  }), { statusLabel: "材料类型已识别，系统正在判断关联", actions: [] });
});

test("server-authorized recovery exposes an explicit continuation without inventing state", () => {
  assert.deepEqual(projectFileProgressionPresentation({
    stage: "PARSED", blocker: "ROLE_ASSESSMENT_PENDING",
    availableActions: ["RESUME_ROLE_ASSESSMENT"], candidateId: null, contractId: null,
    recoveryInputFingerprint: "a".repeat(64),
  }), {
    statusLabel: "材料类型识别尚未完成",
    detailLabel: "解析已完成，可安全接续当前材料的类型识别。",
    actions: [{ key: "RESUME_ROLE_ASSESSMENT", label: "继续识别材料类型" }],
  });
});

test("blocking reason overrides a misleading identity-confirmed stage", () => {
  assert.deepEqual(projectFileProgressionPresentation({
    stage: "IDENTITY_CONFIRMED", blocker: "PROJECT_IDENTITY_NOT_CONFIRMED", availableActions: [],
    candidateId: null, contractId: null,
  }), {
    statusLabel: "系统正在整理材料关联",
    detailLabel: "项目或材料版本已变化，系统会按当前材料重新判断。",
    actions: [],
  });
});

test("candidate materialization and unproven association stay visible as unfinished work", () => {
  assert.deepEqual(projectFileProgressionPresentation({
    stage: "IDENTITY_CONFIRMED", blocker: "CANDIDATE_MATERIALIZATION_PENDING", availableActions: [],
    candidateId: null, contractId: null,
  }), {
    statusLabel: "合同候选尚未形成",
    detailLabel: "材料归属已确认，合同信息仍在整理或需要继续处理。",
    actions: [],
  });
  assert.deepEqual(projectFileProgressionPresentation({
    stage: "IDENTITY_CONFIRMED", blocker: "PROJECT_CONTRACT_ASSOCIATION_UNPROVEN", availableActions: [],
    candidateId: "candidate-1", contractId: null,
  }), {
    statusLabel: "正式合同关联暂未完成",
    detailLabel: "系统暂时无法证明材料与正式合同的持久关联，已停止进入审核。",
    actions: [],
  });
});
