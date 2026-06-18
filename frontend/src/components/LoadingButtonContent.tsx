import type {ReactNode} from 'react';
import {Loader2} from 'lucide-react';

type LoadingButtonContentProps = {
  loading: boolean;
  loadingText: string;
  children: ReactNode;
  className?: string;
  spinnerClassName?: string;
  iconOnly?: boolean;
};

export default function LoadingButtonContent({
  loading,
  loadingText,
  children,
  className = 'inline-flex items-center gap-2',
  spinnerClassName = 'w-4 h-4 animate-spin',
  iconOnly = false,
}: LoadingButtonContentProps) {
  if (!loading) {
    return <>{children}</>;
  }

  if (iconOnly) {
    return <Loader2 className={spinnerClassName} aria-label={loadingText} />;
  }

  return (
    <span className={className}>
      <Loader2 className={spinnerClassName} />
      {loadingText}
    </span>
  );
}
