// frontend/src/components/interviewschedule/InterviewListItem.tsx

import React from 'react';
import { Edit2, Trash2, ExternalLink } from 'lucide-react';
import dayjs from 'dayjs';
import type { InterviewSchedule, InterviewStatus } from '../../types/interviewSchedule';
import {isPendingScheduleStatus, scheduleStatusBadgeConfig} from './statusConfig';

interface InterviewListItemProps {
  interview: InterviewSchedule;
  onEdit: () => void;
  onDelete: () => void;
  onStatusChange: (status: InterviewStatus) => void;
}

const typeLabels: Record<string, string> = {
  ONSITE: '现场面试',
  VIDEO: '视频面试',
  PHONE: '电话面试',
};

export const InterviewListItem: React.FC<InterviewListItemProps> = ({
  interview,
  onEdit,
  onDelete,
  onStatusChange,
}) => {
  return (
    <article className="surface-card p-4 sm:p-5">
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <span className={`status-badge ${scheduleStatusBadgeConfig[interview.status].className}`}>
              {scheduleStatusBadgeConfig[interview.status].label}
            </span>
            <time className="text-sm font-medium text-stone-600 dark:text-stone-400">
              {dayjs(interview.interviewTime).format('YYYY-MM-DD HH:mm')}
            </time>
          </div>

          <h3 className="mb-1 text-lg font-semibold text-stone-900 dark:text-stone-50">
            {interview.companyName}
          </h3>
          <p className="mb-3 text-sm font-medium text-stone-600 dark:text-stone-300">{interview.position}</p>

          <div className="flex flex-wrap items-center gap-2 text-sm text-stone-500 dark:text-stone-400">
            <span className="rounded-md bg-stone-100 px-2 py-1 font-medium dark:bg-stone-800">
              第 {interview.roundNumber} 轮
            </span>
            <span aria-hidden="true">·</span>
            <span className="font-medium">{typeLabels[interview.interviewType] || interview.interviewType}</span>
            {interview.interviewer && (
              <>
                <span aria-hidden="true">·</span>
                <span className="font-medium">面试官：{interview.interviewer}</span>
              </>
            )}
          </div>

          {interview.meetingLink && (
            <a
              href={interview.meetingLink}
              target="_blank"
              rel="noopener noreferrer"
              className="mt-3 inline-flex items-center gap-1.5 text-sm font-medium text-primary-700 hover:text-primary-800 dark:text-primary-400 dark:hover:text-primary-300"
            >
              <ExternalLink className="w-4 h-4" />
              进入会议
            </a>
          )}

          {interview.notes && (
            <p className="mt-3 text-sm text-stone-500 dark:text-stone-400">{interview.notes}</p>
          )}
        </div>

        <div className="flex gap-1">
          <button
            type="button"
            onClick={onEdit}
            className="rounded-lg p-2 text-stone-400 hover:bg-stone-100 hover:text-primary-700 dark:text-stone-500 dark:hover:bg-stone-800 dark:hover:text-primary-400"
            aria-label="编辑日程"
          >
            <Edit2 className="h-4 w-4" />
          </button>
          <button
            type="button"
            onClick={onDelete}
            className="rounded-lg p-2 text-stone-400 hover:bg-red-50 hover:text-red-600 dark:text-stone-500 dark:hover:bg-red-950/30 dark:hover:text-red-400"
            aria-label="删除日程"
          >
            <Trash2 className="h-4 w-4" />
          </button>
        </div>
      </div>

      {isPendingScheduleStatus(interview.status) && (
        <div className="mt-4 flex flex-wrap gap-2 border-t border-stone-200 pt-3 dark:border-stone-800">
          <button
            type="button"
            onClick={() => onStatusChange('COMPLETED')}
            className="rounded-lg border border-emerald-200 px-3 py-1.5 text-sm font-medium text-emerald-700 hover:bg-emerald-50 dark:border-emerald-900 dark:text-emerald-300 dark:hover:bg-emerald-950/30"
          >
            标记已完成
          </button>
          <button
            type="button"
            onClick={() => onStatusChange('CANCELLED')}
            className="btn-secondary px-3 py-1.5 text-sm"
          >
            取消面试
          </button>
        </div>
      )}
    </article>
  );
};
