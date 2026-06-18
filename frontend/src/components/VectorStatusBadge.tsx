import {AlertCircle, CheckCircle, Clock, Loader2} from 'lucide-react';
import type {VectorStatus} from '../api/knowledgebase';

const VECTOR_STATUS_CONFIG = {
  COMPLETED: {
    text: '已完成',
    icon: CheckCircle,
    className: 'text-green-500',
    spinning: false,
  },
  PROCESSING: {
    text: '处理中',
    icon: Loader2,
    className: 'text-blue-500',
    spinning: true,
  },
  PENDING: {
    text: '待处理',
    icon: Clock,
    className: 'text-yellow-500',
    spinning: false,
  },
  FAILED: {
    text: '失败',
    icon: AlertCircle,
    className: 'text-red-500',
    spinning: false,
  },
} satisfies Record<VectorStatus, {
  text: string;
  icon: React.ComponentType<{ className?: string }>;
  className: string;
  spinning: boolean;
}>;

interface VectorStatusBadgeProps {
  status: VectorStatus;
  textClassName?: string;
}

function VectorStatusIcon({ status }: { status: VectorStatus }) {
  const config = VECTOR_STATUS_CONFIG[status];
  const Icon = config.icon;
  const className = `w-4 h-4 ${config.className}${config.spinning ? ' animate-spin' : ''}`;
  return <Icon className={className} />;
}

export default function VectorStatusBadge({
  status,
  textClassName = 'text-sm text-slate-600',
}: VectorStatusBadgeProps) {
  const config = VECTOR_STATUS_CONFIG[status];

  return (
    <div className="flex items-center gap-2">
      <VectorStatusIcon status={status} />
      <span className={textClassName}>{config.text}</span>
    </div>
  );
}
