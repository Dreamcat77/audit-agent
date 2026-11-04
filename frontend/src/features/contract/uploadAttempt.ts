import { isAmbiguousSessionOperation, newSecureOperationKey } from "./sessionOperation";
import type { ReviewDocumentKind } from "./workspaceTypes";

export type UploadAttemptState = "ACTIVE" | "TERMINAL";

/** Browser-lifetime identity for one explicit file upload operation. */
export interface PendingUploadAttempt {
  sessionId: string;
  documentKind: ReviewDocumentKind;
  fileRef: File;
  startingExpectedVersion: number;
  idempotencyKey: string;
  state: UploadAttemptState;
}

export function reuseOrCreateUploadAttempt(
  current: PendingUploadAttempt | undefined,
  sessionId: string,
  documentKind: ReviewDocumentKind,
  fileRef: File,
  currentSessionVersion: number,
): PendingUploadAttempt {
  if (
    current?.state === "ACTIVE"
    && current.sessionId === sessionId
    && current.documentKind === documentKind
    && current.fileRef === fileRef
  ) {
    return current;
  }
  return {
    sessionId,
    documentKind,
    fileRef,
    startingExpectedVersion: currentSessionVersion,
    idempotencyKey: newSecureOperationKey(),
    state: "ACTIVE",
  };
}

export function afterUploadFailure(
  attempt: PendingUploadAttempt,
  error: unknown,
): PendingUploadAttempt {
  return isAmbiguousSessionOperation(error)
    ? attempt
    : { ...attempt, state: "TERMINAL" };
}
