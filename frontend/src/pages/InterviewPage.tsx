import {useCallback, useEffect, useRef, useState} from 'react';
import {motion} from 'framer-motion';
import {interviewApi} from '../api/interview';
import {getErrorMessage} from '../api/request';
import AgentInsightPanel from '../components/AgentInsightPanel';
import ConfirmDialog from '../components/ConfirmDialog';
import InterviewChatPanel from '../components/InterviewChatPanel';
import InterviewPageHeader from '../components/InterviewPageHeader';
import { EmptyState, LoadingState } from '../components/PageState';
import type {InterviewMessage, InterviewQuestion, InterviewSession} from '../types/interview';
import type {Difficulty} from '../components/UnifiedInterviewModal';
import type {CategoryDTO} from '../api/skill';
import { CUSTOM_SKILL_ID } from '../hooks/useInterviewConfig';
import { Mic } from 'lucide-react';

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
}

export default function Interview({
  resumeText,
  resumeId,
  sessionIdToResume,
  initialConfig,
  onBack,
  onInterviewComplete,
}: InterviewProps) {
  const [session, setSession] = useState<InterviewSession | null>(null);
  const [currentQuestion, setCurrentQuestion] = useState<InterviewQuestion | null>(null);
  const [messages, setMessages] = useState<InterviewMessage[]>([]);
  const [answer, setAnswer] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [isCreating, setIsCreating] = useState(false);
  const [showCompleteConfirm, setShowCompleteConfirm] = useState(false);
  const [insightRefresh, setInsightRefresh] = useState(0);
  const [draftSaved, setDraftSaved] = useState(false);
  const startedRef = useRef(false);
  const saveTimerRef = useRef<number>();

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
        jdText: skillId === CUSTOM_SKILL_ID ? jdText : undefined,
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
    } else {
      startInterview();
    }
  }, [resumeExistingSession, sessionIdToResume, startInterview]);

  // 答案暂存（debounce）
  useEffect(() => {
    if (!session || !currentQuestion || !answer.trim()) {
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
  }, [answer, currentQuestion, session]);

  const handleSubmitAnswer = async () => {
    if (!answer.trim() || !session || !currentQuestion) return;

    setIsSubmitting(true);

    const userMessage: InterviewMessage = {
      type: 'user',
      content: answer
    };
    setMessages(prev => [...prev, userMessage]);

    try {
      const response = await interviewApi.submitAnswer({
        sessionId: session.sessionId,
        questionIndex: currentQuestion.questionIndex,
        answer: answer.trim()
      });

      setAnswer('');
      setInsightRefresh(n => n + 1);

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
      setError(getErrorMessage(err, '提交答案失败，请重试'));
      console.error(err);
    } finally {
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
        label="正在生成面试题目..."
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
              className="px-5 py-2 bg-primary-500 text-white rounded-lg hover:bg-primary-600"
            >
              重试
            </button>
            <button
              onClick={onBack}
              className="px-5 py-2 bg-slate-200 dark:bg-slate-700 text-slate-700 dark:text-slate-300 rounded-lg hover:bg-slate-300 dark:hover:bg-slate-600"
            >
              返回
            </button>
          </div>
          }
        />
      </div>
    );
  }

  if (!session || !currentQuestion) return null;

  return (
    <div className="pb-10">
      <InterviewPageHeader
        title="模拟面试"
        subtitle="认真回答每个问题，展示您的实力"
        icon={(
          <Mic className="w-6 h-6 text-white" />
        )}
      />

      <motion.div
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.3 }}
        className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_360px]"
      >
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
        />
        <AgentInsightPanel
          sessionId={session.sessionId}
          refreshKey={insightRefresh}
          className="max-h-[calc(100vh-180px)] xl:sticky xl:top-24"
        />
      </motion.div>

      {/* 提前交卷确认对话框 */}
      <ConfirmDialog
        open={showCompleteConfirm}
        title="提前交卷"
        message="确定要提前交卷吗？未回答的问题将按0分计算。"
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
