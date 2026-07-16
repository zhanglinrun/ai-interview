// frontend/src/components/interviewschedule/CalendarErrorBoundary.tsx

import { Component, type ErrorInfo, type ReactNode } from 'react';

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export class CalendarErrorBoundary extends Component<Props, State> {
  public state: State = {
    hasError: false,
    error: null,
  };

  public static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  public componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('Calendar Error:', error, errorInfo);
  }

  public render() {
    if (this.state.hasError) {
      return (
        <div className="surface-card p-6">
          <div className="py-10 text-center">
            <div className="mb-3 text-3xl" aria-hidden="true">📅</div>
            <h3 className="mb-2 text-lg font-semibold text-stone-900 dark:text-stone-50">
              日历暂时无法显示
            </h3>
            <p className="mb-4 text-sm text-stone-500 dark:text-stone-400">
              请刷新页面后重试。
            </p>
            <button
              type="button"
              onClick={() => {
                this.setState({ hasError: false, error: null });
                window.location.reload();
              }}
              className="btn-primary px-4 py-2 text-sm"
            >
              刷新页面
            </button>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
