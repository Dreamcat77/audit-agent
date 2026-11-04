import type { ReactNode } from "react";
import { AlertTriangle } from "lucide-react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "./ui/alert-dialog";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "./ui/dialog";

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  description: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  loading?: boolean;
  destructive?: boolean;
  error?: ReactNode;
  errorTitle?: string;
  errorActionLabel?: string;
  onOpenChange: (open: boolean) => void;
  onConfirm: () => void;
}

interface NoticeDialogProps {
  open: boolean;
  title: string;
  description: ReactNode;
  actionLabel?: string;
  onOpenChange: (open: boolean) => void;
}

interface FormDialogProps {
  open: boolean;
  title: string;
  description?: ReactNode;
  children: ReactNode;
  submitLabel?: string;
  cancelLabel?: string;
  loading?: boolean;
  submitDisabled?: boolean;
  onOpenChange: (open: boolean) => void;
  onSubmit: () => void;
}

export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel = "确定",
  cancelLabel = "取消",
  loading = false,
  destructive = false,
  error,
  errorTitle = "操作失败",
  errorActionLabel = "知道了",
  onOpenChange,
  onConfirm,
}: ConfirmDialogProps) {
  const failed = error !== undefined && error !== null;
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{failed ? errorTitle : title}</AlertDialogTitle>
          <AlertDialogDescription>
            {failed ? (
              <span className="flex items-start gap-2 rounded border border-red-200 bg-red-50 px-3 py-2 text-left text-red-700">
                <AlertTriangle size={16} className="mt-0.5 shrink-0" />
                <span>{error}</span>
              </span>
            ) : description}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          {failed ? (
            <AlertDialogAction onClick={() => onOpenChange(false)}>{errorActionLabel}</AlertDialogAction>
          ) : <>
            <AlertDialogCancel disabled={loading}>{cancelLabel}</AlertDialogCancel>
            <AlertDialogAction
              disabled={loading}
              onClick={(event) => {
                // Keep the dialog open until the async action confirms success.
                event.preventDefault();
                onConfirm();
              }}
              className={destructive ? "bg-red-600 text-white hover:bg-red-700" : undefined}
            >
              {loading ? "处理中…" : confirmLabel}
            </AlertDialogAction>
          </>}
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

export function NoticeDialog({
  open,
  title,
  description,
  actionLabel = "知道了",
  onOpenChange,
}: NoticeDialogProps) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogAction onClick={() => onOpenChange(false)}>{actionLabel}</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

export function FormDialog({
  open,
  title,
  description,
  children,
  submitLabel = "确定",
  cancelLabel = "取消",
  loading = false,
  submitDisabled = false,
  onOpenChange,
  onSubmit,
}: FormDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description && <DialogDescription>{description}</DialogDescription>}
        </DialogHeader>
        <div className="space-y-4">{children}</div>
        <DialogFooter>
          <button
            type="button"
            disabled={loading}
            onClick={() => onOpenChange(false)}
            className="rounded border border-slate-200 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 disabled:opacity-50"
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            disabled={loading || submitDisabled}
            onClick={onSubmit}
            className="rounded bg-blue-600 px-4 py-2 text-sm text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {loading ? "处理中…" : submitLabel}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
