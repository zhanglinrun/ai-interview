// frontend/src/components/interviewschedule/ScheduleCalendar.tsx

import { Calendar, dayjsLocalizer, View } from 'react-big-calendar';
import withDragAndDrop, { EventInteractionArgs } from 'react-big-calendar/lib/addons/dragAndDrop';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import 'react-big-calendar/lib/css/react-big-calendar.css';
import 'react-big-calendar/lib/addons/dragAndDrop/styles.css';
import './ScheduleCalendar.css';
import type { InterviewSchedule } from '../../types/interviewSchedule';
import { InterviewEvent } from './InterviewEvent';

dayjs.locale('zh-cn');

const localizer = dayjsLocalizer(dayjs);

export interface CalendarInterviewEvent extends InterviewSchedule {
  title: string;
  start: Date;
  end: Date;
}

const DnDCalendar = withDragAndDrop<CalendarInterviewEvent>(Calendar);

interface ScheduleCalendarProps {
  interviews: InterviewSchedule[];
  onSelectEvent: (interview: InterviewSchedule) => void;
  view: View;
  onViewChange: (view: View) => void;
  date: Date;
  onDateChange: (date: Date) => void;
  onEventDrop?: (data: EventInteractionArgs<CalendarInterviewEvent>) => void;
  onEventResize?: (data: EventInteractionArgs<CalendarInterviewEvent>) => void;
}

export const ScheduleCalendar: React.FC<ScheduleCalendarProps> = ({
  interviews = [],
  onSelectEvent,
  view,
  onViewChange,
  date,
  onDateChange,
  onEventDrop,
  onEventResize,
}) => {
  // Filter out invalid interviews and convert to events
  const events: CalendarInterviewEvent[] = interviews
    .filter(interview => {
      if (!interview.interviewTime) return false;
      const d = dayjs(interview.interviewTime);
      return d.isValid();
    })
    .map(interview => {
      const start = dayjs(interview.interviewTime).toDate();
      return {
        ...interview,
        title: interview.companyName || '未知公司',
        start,
        end: dayjs(start).add(30, 'minute').toDate(),
      };
    });

  // Calculate min and max time range based on events
  const getMinMaxTime = () => {
    const currentDay = dayjs(date).startOf('day');
    
    // Default working hours: 08:00 - 22:00
    let minHour = 8;
    let maxHour = 22;
    let hasLateEvent = false;

    // Check events on the CURRENT displayed date/week
    events.forEach(event => {
      const eventStart = dayjs(event.start);
      const eventEnd = dayjs(event.end);
      
      if (eventStart.isValid() && eventEnd.isValid()) {
        const startHour = eventStart.hour();
        const endHour = eventEnd.hour();
        // Adjust minHour for early morning interviews
        if (startHour < minHour) {
          minHour = Math.max(0, startHour);
        }
        
        // Adjust maxHour for late night interviews
        // If it ends at 23:xx or later (including next day), we need the full range
        if (endHour > maxHour || (endHour === 0 && eventEnd.isAfter(eventStart, 'day'))) {
          maxHour = 23;
          hasLateEvent = true;
        } else if (endHour > maxHour) {
          maxHour = Math.min(23, endHour);
        }
      }
    });

    // Ensure minHour < maxHour
    if (minHour >= maxHour) {
      minHour = 8;
      maxHour = 22;
    }

    const minTime = currentDay.hour(minHour).minute(0).second(0).toDate();
    // If we have late events, set max to 23:59:59 to show the very end of the day
    const maxTime = hasLateEvent 
      ? currentDay.hour(23).minute(59).second(59).toDate()
      : currentDay.hour(maxHour).minute(0).second(0).toDate();

    return { minTime, maxTime };
  };

  const { minTime, maxTime } = getMinMaxTime();

  // Final check to prevent react-big-calendar from crashing with invalid min/max
  const isValidRange = minTime instanceof Date && !isNaN(minTime.getTime()) && 
                     maxTime instanceof Date && !isNaN(maxTime.getTime()) && 
                     minTime.getTime() < maxTime.getTime();

  const finalMinTime = isValidRange ? minTime : dayjs(date).startOf('day').add(8, 'hour').toDate();
  const finalMaxTime = isValidRange ? maxTime : dayjs(date).startOf('day').add(22, 'hour').toDate();

  const eventStyleGetter = () => ({
    style: {
      backgroundColor: 'transparent',
      border: 'none',
    }
  });

  const formats = {
    dateFormat: (value: Date) => dayjs(value).format('D'),
    dayFormat: (value: Date) => dayjs(value).format('D日 ddd'),
    weekdayFormat: (value: Date) => dayjs(value).format('ddd'),
    monthHeaderFormat: (value: Date) => dayjs(value).format('YYYY年M月'),
    dayHeaderFormat: (value: Date) => dayjs(value).format('YYYY年M月D日 dddd'),
    dayRangeHeaderFormat: ({ start, end }: { start: Date; end: Date }) =>
      `${dayjs(start).format('M月D日')} - ${dayjs(end).format('M月D日')}`,
    agendaDateFormat: (value: Date) => dayjs(value).format('M月D日 ddd'),
    agendaTimeFormat: (value: Date) => dayjs(value).format('HH:mm'),
    agendaTimeRangeFormat: ({ start, end }: { start: Date; end: Date }) =>
      `${dayjs(start).format('HH:mm')} - ${dayjs(end).format('HH:mm')}`,
    timeGutterFormat: 'HH:mm',
    eventTimeRangeFormat: ({ start, end }: { start: Date; end: Date }) =>
      `${dayjs(start).format('HH:mm')} - ${dayjs(end).format('HH:mm')}`,
  };

  const handleSelectEvent = (event: CalendarInterviewEvent) => {
    onSelectEvent(event);
  };

  return (
    <div className="surface-card overflow-x-auto p-3 sm:p-4">
      <div className="min-w-[720px]">
        <DnDCalendar
          localizer={localizer}
          culture="zh-cn"
          events={events}
          view={view}
          onView={onViewChange}
          date={date}
          onNavigate={onDateChange}
          startAccessor="start"
          endAccessor="end"
          min={finalMinTime}
          max={finalMaxTime}
          step={30}
          timeslots={2}
          style={{ height: 720 }}
          eventPropGetter={eventStyleGetter}
          components={{
            event: InterviewEvent,
          }}
          formats={formats}
          onSelectEvent={handleSelectEvent}
          views={['month', 'week', 'day']}
          toolbar={false}
          messages={{
            today: '今天',
            previous: '上一页',
            next: '下一页',
            month: '月',
            week: '周',
            day: '日',
            agenda: '列表',
            date: '日期',
            time: '时间',
            event: '事件',
            noEventsInRange: '在此范围内没有面试',
          }}
          onEventDrop={onEventDrop}
          onEventResize={onEventResize}
          resizable
          selectable
        />
      </div>
    </div>
  );
};
