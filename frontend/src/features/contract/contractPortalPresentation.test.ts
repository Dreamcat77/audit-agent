import assert from "node:assert/strict";
import test from "node:test";
import { contractPortalUploadNotice } from "./contractPortalPresentation.ts";

const operation = { file: { name: "失败.docx" } as File, idempotencyKey: "retained-key" };

test("cancelled picker has no notice while partial result preserves the Project and retry action", () => {
  assert.equal(contractPortalUploadNotice({ kind: "CANCELLED" }), null);
  assert.deepEqual(contractPortalUploadNotice({
    kind: "PARTIAL", projectId: "project-partial", succeeded: [], unresolved: [operation],
  }), {
    tone: "warning",
    message: "已保留成功材料，1 份材料结果待确认。请继续原操作，避免重复创建审核对象。",
    projectId: "project-partial",
    retryable: true,
  });
});

test("uncertain and complete outcomes use truthful customer language", () => {
  assert.deepEqual(contractPortalUploadNotice({
    kind: "OUTCOME_UNCERTAIN", projectId: null, succeeded: [], unresolved: [operation],
  }), {
    tone: "warning", message: "本次提交结果尚未确认，请使用同一操作继续。", projectId: null, retryable: true,
  });
  assert.deepEqual(contractPortalUploadNotice({
    kind: "COMPLETED", projectId: "project-complete", succeeded: [], unresolved: [],
  }), {
    tone: "success", message: "材料已保存，正在进入材料核验。", projectId: "project-complete", retryable: false,
  });
});

test("async byte acceptance is not described as finished parsing",()=>{
  const notice=contractPortalUploadNotice({kind:"COMPLETED",projectId:"p",succeeded:[],unresolved:[],acceptedAsync:true});
  assert.match(notice!.message,/已接收/);assert.match(notice!.message,/后台/);
  assert.doesNotMatch(notice!.message,/审核完成|解析完成/);
});
