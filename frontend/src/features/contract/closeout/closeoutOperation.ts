import type { CloseoutContext } from "./closeoutTypes.ts";
import { createOperationKey } from "../../../api/operationKey.ts";
import { MaterialSha256 } from "../../procurement/materials/materialSha256.ts";
export type OperationStore = Pick<Storage, "getItem" | "setItem" | "removeItem">;
interface Operation { key: string; fingerprint: string; resourceId?: string; terminal: boolean }
const prefix = "contract-closeout:v1:";
export class CloseoutOperations {
  private readonly storage: OperationStore;
  constructor(storage: OperationStore) { this.storage = storage; }
  read(scope: string): Operation | null {
    const raw = this.storage.getItem(prefix + scope);
    if (raw === null) return null;
    try {
      const value = JSON.parse(raw);
      if (!value || typeof value.key !== "string" || !value.key || typeof value.fingerprint !== "string"
          || typeof value.terminal !== "boolean" || (value.resourceId !== undefined && typeof value.resourceId !== "string")) throw new Error();
      return value;
    } catch { throw new Error("操作记录暂时无法读取，请保留当前页面并联系管理员。"); }
  }
  begin(scope: string, fingerprint: string): string {
    const current = this.read(scope);
    if (current) {
      if (current.fingerprint !== fingerprint) throw new Error("上次操作尚未确认，请恢复原处理意见后重试。");
      return current.key;
    }
    const operation = { key: createOperationKey(), fingerprint, terminal: false };
    // Fail closed before HTTP if browser storage is unavailable.
    this.storage.setItem(prefix + scope, JSON.stringify(operation));
    return operation.key;
  }
  observe(scope: string, resourceId: string, status: string) {
    const current = this.read(scope);
    if (!current) return;
    if (current.resourceId && current.resourceId !== resourceId) throw new Error("操作记录与结果不一致，请联系管理员。");
    this.storage.setItem(prefix + scope, JSON.stringify({ ...current, resourceId, terminal: ["AVAILABLE", "FAILED", "ACKNOWLEDGED"].includes(status) }));
  }
  newVersion(scope: string) {
    const current = this.read(scope);
    if (current && !current.terminal) throw new Error("上次操作尚未确认，请先查询原操作结果。");
    this.storage.removeItem(prefix + scope);
  }
}
export function operationScope(c: CloseoutContext, kind: "ack" | "report", finding?: string) {
  return JSON.stringify([kind, c.actorId, c.projectId, c.contractId, c.runId, finding ?? null]);
}
export async function commandFingerprint(command: readonly unknown[]): Promise<string> {
  return new MaterialSha256().update(new TextEncoder().encode(JSON.stringify(command))).digest();
}
