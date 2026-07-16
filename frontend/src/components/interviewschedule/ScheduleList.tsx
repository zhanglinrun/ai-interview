// frontend/src/components/interviewschedule/ScheduleList.tsx

import React from 'react';
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
      <div className="surface-card py-14">
        <EmptyState
          title="还没有面试日程"
          className="text-center"
          titleClassName="text-stone-500 dark:text-stone-400 text-base font-medium"
        />
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {sortedInterviews.map((interview) => (
        <InterviewListItem
          key={interview.id}
          interview={interview}
          onEdit={() => onEdit(interview)}
          onDelete={() => onDelete(interview.id)}
          onStatusChange={(status) => onStatusChange(interview.id, status)}
        />
      ))}
    </div>
  );
};
