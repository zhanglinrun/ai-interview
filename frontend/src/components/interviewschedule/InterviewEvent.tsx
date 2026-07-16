// frontend/src/components/interviewschedule/InterviewEvent.tsx

import React from 'react';
import type { EventProps } from 'react-big-calendar';
import type { CalendarInterviewEvent } from './ScheduleCalendar';
import {scheduleEventStatusConfig} from './statusConfig';

export const InterviewEvent: React.FC<EventProps<CalendarInterviewEvent>> = ({ event }) => {
  const config = scheduleEventStatusConfig[event.status];

  return (
    <div className={`h-full overflow-hidden rounded-md border p-1.5 ${config.bg} ${config.text} ${config.border}`}>
      <div className="mb-0.5 break-words text-xs font-semibold leading-tight">{event.companyName}</div>
      <div className="break-words text-xs font-medium leading-tight opacity-90">{event.position}</div>
      {event.roundNumber > 1 && (
        <div className="mt-0.5 text-xs font-medium leading-tight opacity-75">第{event.roundNumber}轮</div>
      )}
    </div>
  );
};
