import type {ReactNode} from 'react';
import {createPortal} from 'react-dom';
import LoadingButtonContent from './LoadingButtonContent';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: string | ReactNode;
  confirmText?: string;
  cancelText?: string;
  confirmVariant?: 'danger' | 'primary' | 'warning';
  onConfirm: () => void;
  onCancel: () => void;
  loading?: boolean;
  customContent?: ReactNode;
  hideButtons?: boolean;
  error?: string;
}

export default function ConfirmDialog({
  open,
  title,
  message,
  confirmText = '确定',
  cancelText = '取消',
  confirmVariant = 'primary',
  onConfirm,
  onCancel,
  loading = false,
  customContent,
  hideButtons = false,
  error,
}: ConfirmDialogProps) {
  if (!open || typeof document === 'undefined') return null;

  const variantStyles = {
    danger: 'bg-red-600 hover:bg-red-700',
    primary: 'btn-primary',
    warning: 'bg-amber-600 hover:bg-amber-700',
  };

  return createPortal(
    <div
      className="fixed inset-0 z-[80] flex items-center justify-center bg-black/50 p-4"
      onClick={loading ? undefined : onCancel}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
        onClick={(e) => e.stopPropagation()}
        className="surface-card w-full max-w-md p-6"
      >
        <h3 id="confirm-dialog-title" className="mb-4 text-xl font-bold text-slate-900 dark:text-white">
          {title}
        </h3>

        <div className="mb-6 text-slate-600 dark:text-slate-300">
          {typeof message === 'string' ? (
            message && <p className="whitespace-pre-line">{message}</p>
          ) : (
            message
          )}
          {customContent}
          {error && (
            <p className="mt-3 text-sm text-red-600 dark:text-red-400" role="alert">
              {error}
            </p>
          )}
        </div>

        {!hideButtons && (
          <div className="flex justify-end gap-3">
            <button
              type="button"
              onClick={onCancel}
              disabled={loading}
              className="btn-secondary px-5 py-2.5 font-medium disabled:cursor-not-allowed disabled:opacity-50"
            >
              {cancelText}
            </button>
            <button
              type="button"
              onClick={onConfirm}
              disabled={loading}
              className={`rounded-lg px-5 py-2.5 font-medium text-white disabled:cursor-not-allowed disabled:opacity-50 ${variantStyles[confirmVariant]}`}
            >
              <LoadingButtonContent
                loading={loading}
                loadingText="处理中..."
                spinnerClassName="w-4 h-4 animate-spin text-white"
              >
                {confirmText}
              </LoadingButtonContent>
            </button>
          </div>
        )}
      </div>
    </div>,
    document.body,
  );
}
