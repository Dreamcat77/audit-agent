import { ApiError } from "../../api/client";

export type F02ReviewConflict =
  | "IN_PROGRESS"
  | "TERMINAL_FAILED"
  | "TERMINAL_FINGERPRINT"
  | "OTHER";

const IN_PROGRESS_MESSAGE = "idempotent request is in progress";
const FAILED_MESSAGE = "idempotent request previously failed; use a new idempotency-key";
const FINGERPRINT_MESSAGE = "idempotency key fingerprint conflict";

export function classifyF02ReviewConflict(error: unknown): F02ReviewConflict {
  if (!(error instanceof ApiError) || error.status !== 409) return "OTHER";

  const message = error.message.trim().toLowerCase();
  // TODO: Replace message classification when the backend provides a structured conflict reason.
  if (message.startsWith(IN_PROGRESS_MESSAGE)) return "IN_PROGRESS";
  if (message === FAILED_MESSAGE) return "TERMINAL_FAILED";
  if (message === FINGERPRINT_MESSAGE) return "TERMINAL_FINGERPRINT";
  return "OTHER";
}
