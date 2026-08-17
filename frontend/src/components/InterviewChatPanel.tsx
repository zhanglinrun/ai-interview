import {useMemo, useRef} from 'react';
import {Virtuoso, type VirtuosoHandle} from 'react-virtuoso';
import type {InterviewMessage, InterviewQuestion, InterviewSession} from '../types/interview';
import {Clock, Flag, Send} from 'lucide-react';
import InterviewMessageBubble from './InterviewMessageBubble';
import LoadingButtonContent from './LoadingButtonContent';
import {useElapsedSeconds} from '../hooks/useElapsedSeconds';
import {formatClockTime} from '../utils/format';
import {resolveInterviewStartedAt} from '../utils/interviewTimer';

interface InterviewChatPanelProps {
  session: InterviewSession;
  currentQuestion: InterviewQuestion | null;
  messages: InterviewMessage[];
  answer: string;
  onAnswerChange: (answer: string) => void;
  onSubmit: () => void;
  isSubmitting: boolean;
  onShowCompleteConfirm: (show: boolean) => void;
  draftSaved?: boolean;
  error?: string;
}

/**
 * 面试聊天面板组件
 */
export default function InterviewChatPanel({
  session,
  currentQuestion,
  messages,
  answer,
  onAnswerChange,
  onSubmit,
  isSubmitting,
  onShowCompleteConfirm,
  draftSaved,
  error,
}: InterviewChatPanelProps) {
  const virtuosoRef = useRef<VirtuosoHandle>(null);
  const startedAt = useMemo(
    () => resolveInterviewStartedAt(session.sessionId, session.createdAt),
    [session.createdAt, session.sessionId],
  );
  const elapsedSeconds = useElapsedSeconds(startedAt);

  const progress = useMemo(() => {
    if (!session || !currentQuestion) return 0;
    return ((currentQuestion.questionIndex + 1) / session.totalQuestions) * 100;
  }, [session, currentQuestion]);

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
      onSubmit();
    }
  };

  return (
    <div className="flex h-[calc(100dvh-190px)] min-h-[520px] flex-col">
      {/* 聊天卡片：头部进度 + 消息流 + 输入区一体化 */}
      <div className="surface-card flex-1 overflow-hidden flex flex-col min-h-0">
        {/* 头部：题号 + 分类 + 进度 */}
        <div className="px-6 pt-5 pb-4 border-b border-stone-200/70 dark:border-stone-800">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-3">
              <span className="text-sm font-semibold text-stone-800 dark:text-stone-100">
                第 {currentQuestion ? currentQuestion.questionIndex + 1 : 0} 题
                <span className="text-stone-400 dark:text-stone-500 font-normal"> / 共 {session.totalQuestions} 题</span>
              </span>
              {currentQuestion?.category && (
                <span className="px-2.5 py-0.5 bg-primary-50 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300 text-xs font-medium rounded-full border border-primary-100 dark:border-primary-800/40">
                  {currentQuestion.category}
                </span>
              )}
            </div>
            <div className="flex items-center gap-3">
              <span
                className="inline-flex items-center gap-1.5 text-xs font-medium tabular-nums text-stone-500 dark:text-stone-400"
                aria-label="已用时"
              >
                <Clock className="h-3.5 w-3.5" />
                {formatClockTime(elapsedSeconds)}
              </span>
              <span className="text-xs font-medium tabular-nums text-stone-400 dark:text-stone-500">
                {Math.round(progress)}%
              </span>
            </div>
          </div>
          <div className="h-1.5 bg-stone-100 dark:bg-stone-800 rounded-full overflow-hidden">
            <div
              className="h-full rounded-full bg-primary-600 transition-[width] duration-300"
              style={{width: `${progress}%`}}
            />
          </div>
        </div>

        {/* 消息流 */}
        <div className="flex-1 min-h-0 bg-stone-50/60 dark:bg-stone-950/30">
          <Virtuoso
            ref={virtuosoRef}
            data={messages}
            initialTopMostItemIndex={messages.length - 1}
            followOutput="smooth"
            className="flex-1 h-full"
            itemContent={(_index, msg) => (
              <div className="pb-5 px-6 first:pt-6">
                <InterviewMessageBubble
                  role={msg.type === 'interviewer' ? 'interviewer' : 'user'}
                  text={msg.content}
                  category={msg.category}
                />
              </div>
            )}
          />
        </div>

        {/* 输入区域 */}
        <div className="border-t border-stone-200 bg-white px-4 py-4 dark:border-stone-800 dark:bg-stone-900 sm:px-5">
          {error ? (
            <p className="mb-2 text-sm text-red-600 dark:text-red-400" role="alert">
              {error}
            </p>
          ) : null}
          <textarea
            value={answer}
            onChange={(e) => onAnswerChange(e.target.value)}
            onKeyDown={handleKeyPress}
            placeholder="输入你的回答，可以结合项目背景和具体实现"
            className="dark-input w-full resize-none px-3 py-3 text-[15px] leading-6"
            rows={3}
            disabled={isSubmitting}
          />
          <div className="mt-2.5 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <div className="flex items-center gap-3 text-xs text-stone-400 dark:text-stone-500">
              <span className="hidden sm:inline">Ctrl / Cmd + Enter 提交</span>
              {draftSaved && (
                <span className="inline-flex items-center gap-1 text-emerald-600 dark:text-emerald-400">
                  <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
                  草稿已暂存
                </span>
              )}
            </div>
            <div className="flex items-center justify-end gap-2">
              <button
                onClick={() => onShowCompleteConfirm(true)}
                disabled={isSubmitting}
                className="inline-flex items-center gap-1.5 rounded-lg px-3 py-2 text-sm font-medium text-stone-500 dark:text-stone-400
                  hover:text-amber-600 dark:hover:text-amber-400 hover:bg-amber-50 dark:hover:bg-amber-900/20
                  transition-colors disabled:cursor-not-allowed disabled:opacity-50"
              >
                <Flag className="w-3.5 h-3.5" />
                提前交卷
              </button>
              <button
                onClick={onSubmit}
                disabled={!answer.trim() || isSubmitting}
                className="btn-primary inline-flex items-center gap-2 px-5 py-2 text-sm disabled:cursor-not-allowed disabled:opacity-50"
              >
                <LoadingButtonContent loading={isSubmitting} loadingText="提交中">
                  <Send className="w-4 h-4" />
                  提交回答
                </LoadingButtonContent>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
