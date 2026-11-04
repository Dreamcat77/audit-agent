import assert from "node:assert/strict";
import { registerHooks } from "node:module";
import test from "node:test";

const hooks = registerHooks({
  load(url, context, nextLoad) {
    const loaded = nextLoad(url, context);
    if (!url.endsWith("/src/api/client.ts")) return loaded;
    const source = typeof loaded.source === "string"
      ? loaded.source : Buffer.from(loaded.source ?? []).toString("utf8");
    return { ...loaded, source: `import.meta.env ??= {};\n${source}` };
  },
});
const { ApiError } = await import("./client.ts");
const { progressionRecoveryUserMessage } = await import("./userFacingError.ts");
hooks.deregister();

test("maps only structured recovery classifications to safe guidance", () => {
  assert.equal(progressionRecoveryUserMessage(new ApiError(409, "BUSINESS_ERROR", "private", {
    classification: "RECOVERY_INPUT_CHANGED",
  })), "项目状态已变化，请重新读取当前项目后再继续。");
  assert.equal(progressionRecoveryUserMessage(new ApiError(409, "BUSINESS_ERROR", "private", {
    classification: "RECOVERY_OUTCOME_UNCERTAIN",
  })), "处理结果尚无法确认；已保留本次操作身份，请先核对当前状态，不要新建操作。");
  assert.equal(progressionRecoveryUserMessage(new ApiError(409, "BUSINESS_ERROR", "private", {
    classification: "FACT_SEGMENT_BUDGET_EXCEEDED",
  })), "材料超过当前关键事实提取范围；本次未调用模型，请缩小材料后使用新的操作重试。");
});

test("never infers a classification from arbitrary server message text", () => {
  assert.equal(progressionRecoveryUserMessage(new ApiError(
    409, "BUSINESS_ERROR", "RECOVERY_OUTCOME_UNCERTAIN token=secret"
  )), null);
  assert.equal(progressionRecoveryUserMessage(new Error("RECOVERY_INPUT_CHANGED")), null);
});
