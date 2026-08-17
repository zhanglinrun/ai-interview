import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import InterviewChatPanel from './InterviewChatPanel';
import type { InterviewQuestion, InterviewSession } from '../types/interview';

const session: InterviewSession = {
  sessionId: 'session-1',
  resumeText: '',
  totalQuestions: 8,
  currentQuestionIndex: 0,
  questions: [],
  status: 'IN_PROGRESS',
  sessionVersion: 1,
  createdAt: '2026-08-16T10:00:00Z',
};

const currentQuestion: InterviewQuestion = {
  questionIndex: 0,
  question: '请介绍一个项目',
  type: 'AGENT',
  category: '项目经历',
  userAnswer: null,
  score: null,
  feedback: null,
};

describe('InterviewChatPanel', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('展示从开场时间算起的正计时', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-16T10:05:07Z'));

    render(
      <InterviewChatPanel
        session={session}
        currentQuestion={currentQuestion}
        messages={[{ type: 'interviewer', content: '请介绍一个项目', category: '项目经历' }]}
        answer=""
        onAnswerChange={vi.fn()}
        onSubmit={vi.fn()}
        isSubmitting={false}
        onShowCompleteConfirm={vi.fn()}
      />,
    );

    expect(screen.getByLabelText('已用时')).toHaveTextContent('05:07');
  });

  it('提交失败时在输入区上方显示错误', () => {
    render(
      <InterviewChatPanel
        session={session}
        currentQuestion={currentQuestion}
        messages={[{ type: 'interviewer', content: '请介绍一个项目', category: '项目经历' }]}
        answer="123"
        onAnswerChange={vi.fn()}
        onSubmit={vi.fn()}
        isSubmitting={false}
        onShowCompleteConfirm={vi.fn()}
        error="面试会话版本冲突，请刷新后重试"
      />,
    );

    expect(screen.getByRole('alert')).toHaveTextContent('面试会话版本冲突，请刷新后重试');
  });
});
