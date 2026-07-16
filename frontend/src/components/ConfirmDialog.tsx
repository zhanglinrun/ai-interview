import LoadingButtonContent from './LoadingButtonContent';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  message: string | React.ReactNode;
  confirmText?: string;
  cancelText?: string;
  confirmVariant?: 'danger' | 'primary' | 'warning';
  onConfirm: () => void;
  onCancel: () => void;
  loading?: boolean;
  customContent?: React.ReactNode;
  hideButtons?: boolean;
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
  hideButtons = false
}: ConfirmDialogProps) {
  if (!open) return null;

  const variantStyles = {
    danger: 'bg-red-600 hover:bg-red-700',
    primary: 'btn-primary',
    warning: 'bg-amber-600 hover:bg-amber-700',
  };

  return (
    <>
      <div
        onClick={onCancel}
        className="fixed inset-0 z-50 bg-black/50"
      />

      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="confirm-dialog-title"
          onClick={(e) => e.stopPropagation()}
          className="surface-card w-full max-w-md p-6"
        >
              {/* 标题 */}
              <h3 id="confirm-dialog-title" className="mb-4 text-xl font-bold text-slate-900 dark:text-white">
                {title}
              </h3>

              {/* 内容 */}
                <div className="text-slate-600 dark:text-slate-300 mb-6">
                {typeof message === 'string' ? (
                  message && <p className="whitespace-pre-line">{message}</p>
                ) : (
                  message
                )}
                {customContent}
              </div>

              {/* 按钮 */}
              {!hideButtons && (
                <div className="flex gap-3 justify-end">
                  <button
                    onClick={onCancel}
                    disabled={loading}
                    className="btn-secondary px-5 py-2.5 font-medium disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {cancelText}
                  </button>
                  <button
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
      </div>
    </>
  );
}
