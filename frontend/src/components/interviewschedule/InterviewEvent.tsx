// frontend/src/components/interviewschedule/InterviewEvent.tsx

import React from 'react';
import { motion } from 'framer-motion';
import type { EventProps } from 'react-big-calendar';
import type { CalendarInterviewEvent } from './ScheduleCalendar';
import {scheduleEventStatusConfig} from './statusConfig';

export const InterviewEvent: React.FC<EventProps<CalendarInterviewEvent>> = ({ event }) => {
  const config = scheduleEventStatusConfig[event.status];

  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.9 }}
      animate={{ opacity: 1, scale: 1 }}
      whileHover={{ scale: 1.02 }}
      className={`p-1.5 rounded-lg ${config.bg} ${config.text} border ${config.border} shadow-md ${config.shadow} backdrop-blur-sm h-full overflow-hidden`}
    >
      <div className="font-display font-semibold text-xs leading-tight mb-0.5 break-words">{event.companyName}</div>
      <div className="text-xs opacity-90 font-medium leading-tight break-words">{event.position}</div>
      {event.roundNumber > 1 && (
        <div className="text-xs opacity-75 mt-0.5 font-medium leading-tight">第{event.roundNumber}轮</div>
      )}
    </motion.div>
  );
};
