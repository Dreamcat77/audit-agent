import type { ContractMaterialIntakeResult } from "./contractMaterialIntakeOperation.ts";

export interface ContractPortalUploadNotice {
  tone: "success" | "warning";
  message: string;
  projectId: string | null;
  retryable: boolean;
}

export function contractPortalUploadNotice(
  result: ContractMaterialIntakeResult,
): ContractPortalUploadNotice | null {
  switch (result.kind) {
    case "CANCELLED": return null;
    case "COMPLETED": return {
      tone: "success", message: result.acceptedAsync?"文件已接收，解析/OCR在后台排队处理，可离开页面后查看真实进度。":"材料已保存，正在进入材料核验。",
      projectId: result.projectId, retryable: false,
    };
    case "PARTIAL": return {
      tone: "warning",
      message: `已保留成功材料，${result.unresolved.length} 份材料结果待确认。请继续原操作，避免重复创建审核对象。`,
      projectId: result.projectId, retryable: true,
    };
    case "OUTCOME_UNCERTAIN": return {
      tone: "warning", message: "本次提交结果尚未确认，请使用同一操作继续。",
      projectId: result.projectId, retryable: true,
    };
  }
}
