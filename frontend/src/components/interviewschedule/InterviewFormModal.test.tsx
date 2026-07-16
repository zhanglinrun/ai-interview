import {render} from '@testing-library/react';
import {afterEach, describe, expect, it, vi} from 'vitest';
import type {InterviewFormData} from '../../types/interviewSchedule';
import {InterviewFormModal} from './InterviewFormModal';

describe('InterviewFormModal', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('把后端可空字段归一为空字符串后再回显', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const nullableData = {
      id: 1,
      companyName: '示例公司',
      position: 'Java 后端工程师',
      interviewTime: '2026-07-16T19:30:00',
      interviewType: 'VIDEO',
      meetingLink: null,
      roundNumber: 1,
      interviewer: null,
      notes: null,
    } as unknown as InterviewFormData;

    const {container} = render(
      <InterviewFormModal
        isOpen
        mode="edit"
        initialData={nullableData}
        onClose={vi.fn()}
        onSubmit={vi.fn()}
      />,
    );

    const meetingLink = container.querySelector<HTMLInputElement>('input[type="url"]');
    const textInputs = container.querySelectorAll<HTMLInputElement>('input[type="text"]');
    const notes = container.querySelector<HTMLTextAreaElement>('textarea');
    expect(meetingLink).toHaveValue('');
    expect(textInputs[2]).toHaveValue('');
    expect(notes).toHaveValue('');
    expect(consoleError).not.toHaveBeenCalled();
  });
});
