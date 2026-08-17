import {useCallback, useEffect, useRef, useState} from 'react';
import {interviewApi} from '../api/interview';
import {getErrorMessage} from '../api/request';
import ConfirmDialog from '../components/ConfirmDialog';
import InterviewChatPanel from '../components/InterviewChatPanel';
import InterviewPageHeader from '../components/InterviewPageHeader';
import { EmptyState, LoadingState } from '../components/PageState';
import type {
  CategoryDTO,
  Difficulty,
  InterviewMessage,
  InterviewQuestion,
  InterviewSession,
} from '../types/interview';
import { MessagesSquare } from 'lucide-react';

const CUSTOM_SKILL_ID = 'custom';

interface InterviewProps {
  resumeText: string;
  resumeId?: number;
  sessionIdToResume?: string;
  initialConfig?: {
    questionCount?: number;
    llmProvider?: string;
    skillId?: string;
    difficulty?: Difficulty;
    customCategories?: CategoryDTO[];
    jdText?: string;
    knowledgeBaseIds?: number[];
  };
  onBack: () => void;
  onInterviewComplete: () => void;
  /** 准备页确认后，或恢复未完成场次时才自动建场。 */
  autoStart?: boolean;
}

export default function Interview({
  resumeText,
  resumeId,
  sessionIdToResume,
  initialConfig,
  onBack,
  onInterviewComplete,
  autoStart = false,
}: InterviewProps) {
  const [session, setSession] = useState<InterviewSession | null>(null);
  const [currentQuestion, setCurrentQuestion] = useState<InterviewQuestion | null>(null);
  const [messages, setMessages] = useState<InterviewMessage[]>([]);
  const [answer, setAnswer] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [isCreating, setIsCreating] = useState(false);
  const [showCompleteConfirm, setShowCompleteConfirm] = useState(false);
  const [draftSaved, setDraftSaved] = useState(false);
  const startedRef = useRef(false);
  const saveTimerRef = useRef<number>();
  const submitCommandRef = useRef(new Map<string, string>());
  const submittingRef = useRef(false);

  const questionCount = initialConfig?.questionCount ?? 8;
  const llmProvider = initialConfig?.llmProvider ?? '';
  const skillId = initialConfig?.skillId ?? 'java-backend';
  const difficulty = initialConfig?.difficulty ?? 'mid';
  const customCategories = initialConfig?.customCategories;
  const jdText = initialConfig?.jdText;
  const knowledgeBaseIds = initialConfig?.knowledgeBaseIds;

  const initSession = useCallback((s: InterviewSession) => {
    setSession(s);

    if (s.questions.length > 0) {
      const idx = Math.min(s.currentQuestionIndex, s.questions.length - 1);
      const currentQ = s.questions[idx];
      setCurrentQuestion(currentQ);

      // 重建消息历史
      const restoredMessages: InterviewMessage[] = [];
      for (let i = 0; i <= idx; i++) {
        const q = s.questions[i];
        restoredMessages.push({
          type: 'interviewer',
          content: q.question,
          category: q.category,
        });
        if (q.userAnswer) {
          restoredMessages.push({
            type: 'user',
            content: q.userAnswer
          });
        }
      }
      setMessages(restoredMessages);
    }
  }, []);

  const startInterview = useCallback(async () => {
    setIsCreating(true);
    setError('');

    try {
      const newSession = await interviewApi.createSession({
        resumeText,
        questionCount,
        resumeId,
        forceCreate: true,
        llmProvider,
        skillId,
        difficulty,
        customCategories: skillId === CUSTOM_SKILL_ID ? customCategories : undefined,
        jdText: jdText && jdText.trim() ? jdText.trim() : undefined,
        knowledgeBaseIds: knowledgeBaseIds && knowledgeBaseIds.length > 0 ? knowledgeBaseIds : undefined,
      });

      initSession(newSession);
    } catch (err) {
      setError(getErrorMessage(err, '创建面试失败，请重试'));
      console.error(err);
    } finally {
      setIsCreating(false);
    }
  }, [
    customCategories,
    difficulty,
    initSession,
    jdText,
    knowledgeBaseIds,
    llmProvider,
    questionCount,
    resumeId,
    resumeText,
    skillId,
  ]);

  const resumeExistingSession = useCallback(async (sessionId: string) => {
    setIsCreating(true);
    setError('');

    try {
      const existingSession = await interviewApi.getSession(sessionId);
      initSession(existingSession);

      // 恢复已填写的答案
      const currentQ = existingSession.questions[existingSession.currentQuestionIndex];
      if (currentQ?.userAnswer) {
        setAnswer(currentQ.userAnswer);
      }
    } catch (err) {
      setError(getErrorMessage(err, '恢复面试失败，请重试'));
      console.error(err);
    } finally {
      setIsCreating(false);
    }
  }, [initSession]);

  // 自动开始面试（恢复已有会话 或 创建新会话）
  useEffect(() => {
    if (startedRef.current) {
      return;
    }
    startedRef.current = true;
    if (sessionIdToResume) {
      resumeExistingSession(sessionIdToResume);
    } else if (autoStart) {
      startInterview();
    }
  }, [autoStart, resumeExistingSession, sessionIdToResume, startInterview]);

  const recoverAfterSubmitFailure = useCallback(async (
    sessionId: string,
    submittedIndex: number,
  ): Promise<boolean> => {
    const existing = await interviewApi.getSession(sessionId);
    const advanced = existing.currentQuestionIndex > submittedIndex
      || existing.status === 'COMPLETED'
      || existing.status === 'EVALUATED';
    initSession(existing);
    if (!advanced) {
      return false;
    }
    setAnswer('');
    setError('');
    if (existing.status === 'COMPLETED' || existing.status === 'EVALUATED') {
      onInterviewComplete();
    }
    return true;
  }, [initSession, onInterviewComplete]);

  // 答案暂存（debounce）
  useEffect(() => {
    if (!session || !currentQuestion || !answer.trim() || isSubmitting) {
      return;
    }
    window.clearTimeout(saveTimerRef.current);
    saveTimerRef.current = window.setTimeout(async () => {
      try {
        await interviewApi.saveAnswer({
          sessionId: session.sessionId,
          questionIndex: currentQuestion.questionIndex,
          answer: answer.trim(),
        });
        setDraftSaved(true);
      } catch {
        // 暂存失败静默，不打扰答题
      }
    }, 1500);
    return () => window.clearTimeout(saveTimerRef.current);
  }, [answer, currentQuestion, isSubmitting, session]);

  const handleSubmitAnswer = async () => {
    if (!answer.trim() || !session || !currentQuestion || submittingRef.current) return;

    submittingRef.current = true;
    setIsSubmitting(true);
    setError('');
    window.clearTimeout(saveTimerRef.current);

    const submittedIndex = currentQuestion.questionIndex;
    const userMessage: InterviewMessage = {
      type: 'user',
      content: answer
    };
    setMessages(prev => [...prev, userMessage]);

    try {
      const commandKey = `${session.sessionId}:${currentQuestion.questionIndex}`;
      const commandId = submitCommandRef.current.get(commandKey) ?? `cmd-${crypto.randomUUID()}`;
      submitCommandRef.current.set(commandKey, commandId);
      const response = await interviewApi.submitAnswer({
        sessionId: session.sessionId,
        commandId,
        expectedSessionVersion: session.sessionVersion,
        questionIndex: currentQuestion.questionIndex,
        answer: answer.trim()
      });

      setSession(prev => prev ? {...prev, sessionVersion: response.sessionVersion} : prev);

      setAnswer('');

      const nextQuestion = response.nextQuestion;
      if (response.hasNextQuestion && nextQuestion) {
        setCurrentQuestion(nextQuestion);
        setMessages(prev => [...prev, {
          type: 'interviewer',
          content: nextQuestion.question,
          category: nextQuestion.category,
        }]);
      } else {
        onInterviewComplete();
      }
    } catch (err) {
      console.error(err);
      try {
        if (await recoverAfterSubmitFailure(session.sessionId, submittedIndex)) {
          return;
        }
      } catch (recoverErr) {
        console.error(recoverErr);
      }
      setError(getErrorMessage(err, '提交答案失败，请重试'));
    } finally {
      submittingRef.current = false;
      setIsSubmitting(false);
    }
  };

  const handleCompleteEarly = async () => {
    if (!session) return;

    setIsSubmitting(true);
    try {
      await interviewApi.completeInterview(session.sessionId);
      setShowCompleteConfirm(false);
      onInterviewComplete();
    } catch (err) {
      setError(getErrorMessage(err, '提前交卷失败，请重试'));
      console.error(err);
    } finally {
      setIsSubmitting(false);
    }
  };

  // 加载中
  if (isCreating) {
    return (
      <LoadingState
        label="正在准备本场面试..."
        className="flex flex-col items-center justify-center min-h-[50vh] gap-3"
        spinnerClassName="w-10 h-10 text-primary-500 animate-spin"
      />
    );
  }

  // 错误状态
  if (error && !session) {
    return (
      <div className="min-h-[50vh] flex items-center justify-center">
        <EmptyState
          title={error}
          className="text-center"
          titleClassName="text-red-500 dark:text-red-400 mb-4"
          action={
          <div className="flex gap-3 justify-center">
            <button
              onClick={startInterview}
              className="btn-primary px-4 py-2 text-sm"
            >
              重试
            </button>
            <button
              onClick={onBack}
              className="btn-secondary px-4 py-2 text-sm"
            >
              返回
            </button>
          </div>
          }
        />
      </div>
    );
  }

  if (!session || !currentQuestion) {
    return (
      <div className="min-h-[50vh] flex items-center justify-center">
        <EmptyState
          title={error || (session ? '本场面试没有可继续的题目' : '面试尚未开始')}
          description={session
            ? '可以返回记录页查看报告，或重新开一场。'
            : '创建或恢复会话后才能开始答题。'}
          className="text-center"
          action={
            <div className="mt-4 flex gap-3 justify-center">
              {!session && !sessionIdToResume ? (
                <button
                  onClick={startInterview}
                  className="btn-primary px-4 py-2 text-sm"
                >
                  重试
                </button>
              ) : null}
              <button
                onClick={onBack}
                className="btn-secondary px-4 py-2 text-sm"
              >
                返回
              </button>
            </div>
          }
        />
      </div>
    );
  }

  return (
    <div className="pb-10">
      <InterviewPageHeader
        title="模拟面试"
        subtitle="按真实面试节奏逐题作答，答案会自动暂存"
        icon={(
          <MessagesSquare className="h-5 w-5" />
        )}
      />

      <InterviewChatPanel
        session={session}
        currentQuestion={currentQuestion}
        messages={messages}
        answer={answer}
        onAnswerChange={(value) => {
          setAnswer(value);
          setDraftSaved(false);
        }}
        onSubmit={handleSubmitAnswer}
        isSubmitting={isSubmitting}
        onShowCompleteConfirm={setShowCompleteConfirm}
        draftSaved={draftSaved}
        error={error}
      />

      {/* 提前交卷确认对话框 */}
      <ConfirmDialog
        open={showCompleteConfirm}
        title="提前交卷"
        message="确定要提前交卷吗？未回答的问题将不会计分。"
        confirmText="确定交卷"
        cancelText="取消"
        confirmVariant="warning"
        loading={isSubmitting}
        onConfirm={handleCompleteEarly}
        onCancel={() => setShowCompleteConfirm(false)}
      />
    </div>
  );
}
