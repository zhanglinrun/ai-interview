// frontend/src/components/interviewschedule/ScheduleList.tsx

import React from 'react';
import { motion } from 'framer-motion';
import type { InterviewSchedule, InterviewStatus } from '../../types/interviewSchedule';
import { EmptyState } from '../PageState';
import { InterviewListItem } from './InterviewListItem';
import { compareDateAsc } from '../../utils/date';

interface ScheduleListProps {
  interviews: InterviewSchedule[];
  onEdit: (interview: InterviewSchedule) => void;
  onDelete: (id: number) => void;
  onStatusChange: (id: number, status: InterviewStatus) => void;
}

export const ScheduleList: React.FC<ScheduleListProps> = ({
  interviews = [],
  onEdit,
  onDelete,
  onStatusChange,
}) => {
  const sortedInterviews = [...interviews].sort(
    (a, b) => compareDateAsc(a.interviewTime, b.interviewTime)
  );

  if (sortedInterviews.length === 0) {
    return (
      <div className="py-16">
        <EmptyState
          title="暂无面试记录"
          className="text-center bg-white/80 dark:bg-slate-900/80 backdrop-blur-xl rounded-2xl border border-slate-200/50 dark:border-slate-700/50 p-12 shadow-xl"
          titleClassName="text-slate-500 dark:text-slate-400 text-lg font-medium"
        />
      </div>
    );
  }

  return (
    <div className="bg-white/80 dark:bg-slate-900/80 backdrop-blur-xl rounded-2xl border border-slate-200/50 dark:border-slate-700/50 p-6 space-y-4">
      {sortedInterviews.map((interview, index) => (
        <motion.div
          key={interview.id}
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.2, delay: index * 0.05 }}
        >
          <InterviewListItem
            interview={interview}
            onEdit={() => onEdit(interview)}
            onDelete={() => onDelete(interview.id)}
            onStatusChange={(status) => onStatusChange(interview.id, status)}
          />
        </motion.div>
      ))}
    </div>
  );
};
