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

const UNKNOWN_STATUS_CONFIG = {
  text: '未知状态',
  icon: Clock,
  className: 'text-slate-400',
  spinning: false,
};

interface VectorStatusBadgeProps {
  status?: DocStatus | null;
  textClassName?: string;
}

function getStatusConfig(status?: DocStatus | null) {
  return status ? DOC_STATUS_CONFIG[status] ?? UNKNOWN_STATUS_CONFIG : UNKNOWN_STATUS_CONFIG;
}

function VectorStatusIcon({ status }: { status?: DocStatus | null }) {
  const config = getStatusConfig(status);
  const Icon = config.icon;
  const className = `w-4 h-4 ${config.className}${config.spinning ? ' animate-spin' : ''}`;
  return <Icon className={className} />;
}

export default function VectorStatusBadge({
  status,
  textClassName = 'text-sm text-slate-600',
}: VectorStatusBadgeProps) {
  const config = getStatusConfig(status);

  return (
    <div className="flex items-center gap-2">
      <VectorStatusIcon status={status} />
      <span className={textClassName}>{config.text}</span>
    </div>
  );
}
