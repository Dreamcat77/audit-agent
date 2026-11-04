import { ApiError } from "./client.ts";

const NETWORK_MESSAGE = "网络连接异常，请稍后重试。";
const UNAUTHENTICATED_MESSAGE = "登录状态已失效，请重新登录。";
const FORBIDDEN_MESSAGE = "当前账号无权限执行此操作。";

/**
 * Maps transport failures to stable customer-facing text. Backend and browser
 * exception messages are deliberately never returned from this boundary.
 */
export function userFacingError(error: unknown, fallback: string): string {
  if (!(error instanceof ApiError)) return fallback;
  if (error.code === "NETWORK_ERROR" || error.status === 0) return NETWORK_MESSAGE;
  if (error.status === 401) return UNAUTHENTICATED_MESSAGE;
  if (error.status === 403) return FORBIDDEN_MESSAGE;
  return fallback;
}

/** Maps only the progression endpoint's structured classification field. */
export function progressionRecoveryUserMessage(error: unknown): string | null {
  if (!(error instanceof ApiError) || error.status !== 409
      || !error.data || typeof error.data !== "object") return null;
  const classification = (error.data as { classification?: unknown }).classification;
  switch (classification) {
    case "RECOVERY_INPUT_CHANGED":
      return "项目状态已变化，请重新读取当前项目后再继续。";
    case "RECOVERY_OUTCOME_UNCERTAIN":
      return "处理结果尚无法确认；已保留本次操作身份，请先核对当前状态，不要新建操作。";
    case "FACT_SEGMENT_BUDGET_EXCEEDED":
      return "材料超过当前关键事实提取范围；本次未调用模型，请缩小材料后使用新的操作重试。";
    default:
      return null;
  }
}
