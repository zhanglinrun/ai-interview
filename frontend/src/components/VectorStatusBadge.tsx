import {CheckCircle, Clock, Loader2} from 'lucide-react';
import type {DocStatus} from '../api/knowledgebase';

// 文档状态展示配置（对齐后端 DocumentStatus 状态机）
const DOC_STATUS_CONFIG = {
  VECTOR_STORED: {
    text: '已完成',
    icon: CheckCircle,
    className: 'text-green-500',
    spinning: false,
  },
  STORED: {
    text: '已完成',
    icon: CheckCircle,
    className: 'text-green-500',
    spinning: false,
  },
  CONVERTING: {
    text: '处理中',
    icon: Loader2,
    className: 'text-blue-500',
    spinning: true,
  },
  CONVERTED: {
    text: '处理中',
    icon: Loader2,
    className: 'text-blue-500',
    spinning: true,
  },
  CHUNKED: {
    text: '处理中',
    icon: Loader2,
    className: 'text-blue-500',
    spinning: true,
  },
  UPLOADED: {
    text: '待处理',
    icon: Clock,
    className: 'text-yellow-500',
    spinning: false,
  },
  INIT: {
    text: '待处理',
    icon: Clock,
    className: 'text-yellow-500',
    spinning: false,
  },
} satisfies Record<DocStatus, {
  text: string;
  icon: React.ComponentType<{ className?: string }>;
  className: string;
  spinning: boolean;
}>;

interface VectorStatusBadgeProps {
  status: DocStatus;
  textClassName?: string;
}

function VectorStatusIcon({ status }: { status: DocStatus }) {
  const config = DOC_STATUS_CONFIG[status];
  const Icon = config.icon;
  const className = `w-4 h-4 ${config.className}${config.spinning ? ' animate-spin' : ''}`;
  return <Icon className={className} />;
}

export default function VectorStatusBadge({
  status,
  textClassName = 'text-sm text-slate-600',
}: VectorStatusBadgeProps) {
  const config = DOC_STATUS_CONFIG[status];

  return (
    <div className="flex items-center gap-2">
      <VectorStatusIcon status={status} />
      <span className={textClassName}>{config.text}</span>
    </div>
  );
}
