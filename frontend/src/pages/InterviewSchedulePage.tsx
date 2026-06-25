// frontend/src/pages/InterviewSchedulePage.tsx

import React, { useState, useCallback } from 'react';
import type { View } from 'react-big-calendar';
import type {EventInteractionArgs} from 'react-big-calendar/lib/addons/dragAndDrop';
import dayjs from 'dayjs';
import { getErrorMessage } from '../api/request';
import { useInterviewSchedule } from '../hooks/useInterviewSchedule';
import { ScheduleHeader, type ScheduleView } from '../components/interviewschedule/ScheduleHeader';
import { ScheduleCalendar, type CalendarInterviewEvent } from '../components/interviewschedule/ScheduleCalendar';
import { ScheduleList } from '../components/interviewschedule/ScheduleList';
import { InterviewFormModal } from '../components/interviewschedule/InterviewFormModal';
import { CalendarErrorBoundary } from '../components/interviewschedule/CalendarErrorBoundary';
import ConfirmDialog from '../components/ConfirmDialog';
import DeleteConfirmDialog from '../components/DeleteConfirmDialog';
import { ErrorState, LoadingState } from '../components/PageState';
import type { InterviewSchedule, InterviewFormData, InterviewStatus } from '../types/interviewSchedule';

function buildRescheduledInterview(
  interview: InterviewSchedule,
  interviewTime: Date,
): InterviewFormData {
  return {
    companyName: interview.companyName,
    position: interview.position,
    interviewTime: dayjs(interviewTime).format('YYYY-MM-DDTHH:mm:ss'),
    interviewType: interview.interviewType,
    meetingLink: interview.meetingLink,
    roundNumber: interview.roundNumber,
    interviewer: interview.interviewer,
    notes: interview.notes,
  };
}

type CalendarChangeData = {
  event: CalendarInterviewEvent;
  start: Date | string;
  end: Date | string;
};

type CalendarChangeArgs = EventInteractionArgs<CalendarChangeData['event']>;

type CalendarView = Exclude<ScheduleView, 'list'>;

function isCalendarView(view: ScheduleView): view is CalendarView {
  return view !== 'list';
}

function toScheduleCalendarView(view: View): CalendarView {
  if (view === 'day' || view === 'month') {
    return view;
  }
  return 'week';
}

export const InterviewSchedulePage: React.FC = () => {
  const {
    interviews,
    loading,
    error,
    createInterview,
    updateInterview,
    deleteInterview,
    updateStatus,
  } = useInterviewSchedule();

  const [view, setView] = useState<ScheduleView>('week');
  const [date, setDate] = useState(new Date());
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [modalMode, setModalMode] = useState<'create' | 'edit'>('create');
  const [selectedInterview, setSelectedInterview] = useState<InterviewSchedule | null>(null);
  const [pendingChanges, setPendingChanges] = useState<Map<number, Date>>(new Map());
  const [isConfirmOpen, setIsConfirmOpen] = useState(false);
  const [isDeleteConfirmOpen, setIsDeleteConfirmOpen] = useState(false);
  const [interviewToDelete, setInterviewToDelete] = useState<number | null>(null);

  const handleAddClick = useCallback(() => {
    setModalMode('create');
    setSelectedInterview(null);
    setIsModalOpen(true);
  }, []);

  const handleEditClick = useCallback((interview: InterviewSchedule) => {
    setModalMode('edit');
    setSelectedInterview(interview);
    setIsModalOpen(true);
  }, []);

  const handleDeleteClick = useCallback((id: number) => {
    setInterviewToDelete(id);
    setIsDeleteConfirmOpen(true);
  }, []);

  const handleConfirmDelete = useCallback(async () => {
    if (interviewToDelete) {
      await deleteInterview(interviewToDelete);
      setInterviewToDelete(null);
    }
    setIsDeleteConfirmOpen(false);
  }, [interviewToDelete, deleteInterview]);

  const handleStatusChange = useCallback(async (id: number, status: InterviewStatus) => {
    await updateStatus(id, status);
  }, [updateStatus]);

  const handleCalendarTimeChange = useCallback(async (
    data: CalendarChangeArgs,
    fallbackMessage: string,
    logMessage: string,
  ) => {
    const eventId = data.event.id;
    const interview = interviews.find(i => i.id === eventId);

    if (interview) {
      try {
        const startDate = typeof data.start === 'string' ? new Date(data.start) : data.start;
        await updateInterview(eventId, buildRescheduledInterview(interview, startDate));
      } catch (error) {
        console.error(logMessage, error);
        alert(getErrorMessage(error, fallbackMessage));
      }
    }
  }, [interviews, updateInterview]);

  const handleEventDrop = useCallback(async (data: CalendarChangeArgs) => {
    await handleCalendarTimeChange(
      data,
      '更新面试时间失败，请重试',
      'Failed to update interview time:',
    );
  }, [handleCalendarTimeChange]);

  const handleEventResize = useCallback(async (data: CalendarChangeArgs) => {
    await handleCalendarTimeChange(
      data,
      '更新面试时长失败，请重试',
      'Failed to update interview duration:',
    );
  }, [handleCalendarTimeChange]);

  const handleFormSubmit = useCallback(async (data: InterviewFormData) => {
    if (modalMode === 'create') {
      await createInterview(data);
    } else if (selectedInterview) {
      await updateInterview(selectedInterview.id, data);
    }
    setIsModalOpen(false);
    setSelectedInterview(null);
  }, [modalMode, selectedInterview, createInterview, updateInterview]);

  const handleConfirmChanges = useCallback(async () => {
    for (const [id, newTime] of pendingChanges) {
      const interview = interviews.find(i => i.id === id);
      if (interview) {
        await updateInterview(id, buildRescheduledInterview(interview, newTime));
      }
    }
    setPendingChanges(new Map());
    setIsConfirmOpen(false);
  }, [pendingChanges, interviews, updateInterview]);

  const handleCancelChanges = useCallback(() => {
    setPendingChanges(new Map());
    setIsConfirmOpen(false);
  }, []);

  if (loading) {
    return (
      <LoadingState
        className="flex items-center justify-center min-h-[50vh]"
        spinnerClassName="w-10 h-10 text-primary-500 animate-spin"
      />
    );
  }

  if (error) {
    return (
      <ErrorState
        title={error}
        className="text-center py-12"
      />
    );
  }

  return (
    <div className="max-w-7xl mx-auto p-6">
      <ScheduleHeader
        view={view}
        onViewChange={setView}
        date={date}
        onDateChange={setDate}
        onAddClick={handleAddClick}
      />

      {!isCalendarView(view) ? (
        <ScheduleList
          interviews={interviews}
          onEdit={handleEditClick}
          onDelete={handleDeleteClick}
          onStatusChange={handleStatusChange}
        />
      ) : (
        <CalendarErrorBoundary>
          <ScheduleCalendar
            interviews={interviews}
            onSelectEvent={handleEditClick}
            view={view}
            onViewChange={(nextView) => setView(toScheduleCalendarView(nextView))}
            date={date}
            onDateChange={setDate}
            onEventDrop={handleEventDrop}
            onEventResize={handleEventResize}
          />
        </CalendarErrorBoundary>
      )}

      <InterviewFormModal
        isOpen={isModalOpen}
        onClose={() => {
          setIsModalOpen(false);
          setSelectedInterview(null);
        }}
        onSubmit={handleFormSubmit}
        onDelete={(id) => {
          setIsModalOpen(false);
          setSelectedInterview(null);
          handleDeleteClick(id);
        }}
        initialData={selectedInterview || undefined}
        mode={modalMode}
      />

      <ConfirmDialog
        open={isConfirmOpen}
        title="确认调整面试时间"
        message={`您调整了 ${pendingChanges.size} 个面试的时间,确认保存吗?`}
        onConfirm={handleConfirmChanges}
        onCancel={handleCancelChanges}
      />

      <DeleteConfirmDialog
        open={isDeleteConfirmOpen}
        item={interviewToDelete ? { id: interviewToDelete } : null}
        itemType="面试"
        customMessage="确定要删除这个面试吗?此操作无法撤销。"
        onConfirm={handleConfirmDelete}
        onCancel={() => {
          setIsDeleteConfirmOpen(false);
          setInterviewToDelete(null);
        }}
      />
    </div>
  );
};

export default InterviewSchedulePage;
