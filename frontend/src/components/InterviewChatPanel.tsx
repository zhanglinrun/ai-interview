import {useMemo, useRef} from 'react';
import {motion} from 'framer-motion';
import {Virtuoso, type VirtuosoHandle} from 'react-virtuoso';
import type {InterviewMessage, InterviewQuestion, InterviewSession} from '../types/interview';
import {Flag, Send} from 'lucide-react';
import InterviewMessageBubble from './InterviewMessageBubble';
import LoadingButtonContent from './LoadingButtonContent';

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
}: InterviewChatPanelProps) {
  const virtuosoRef = useRef<VirtuosoHandle>(null);

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
    <div className="flex flex-col h-[calc(100vh-190px)] min-h-[520px]">
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
            <span className="text-xs font-medium tabular-nums text-stone-400 dark:text-stone-500">
              {Math.round(progress)}%
            </span>
          </div>
          <div className="h-1.5 bg-stone-100 dark:bg-stone-800 rounded-full overflow-hidden">
            <motion.div
              className="h-full bg-gradient-to-r from-primary-500 to-primary-400 rounded-full"
              initial={{ width: 0 }}
              animate={{ width: `${progress}%` }}
              transition={{ duration: 0.4, ease: 'easeOut' }}
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
        <div className="border-t border-stone-200/70 dark:border-stone-800 px-5 py-4 bg-white/80 dark:bg-stone-900/70 backdrop-blur">
          <textarea
            value={answer}
            onChange={(e) => onAnswerChange(e.target.value)}
            onKeyDown={handleKeyPress}
            placeholder="组织你的回答，讲清原理再结合场景…"
            className="w-full px-4 py-3 text-[15px] leading-6 border border-stone-200 dark:border-stone-700 rounded-xl
              focus:outline-none focus:ring-2 focus:ring-primary-500/40 focus:border-primary-500 resize-none
              bg-white dark:bg-stone-900 text-stone-900 dark:text-stone-100
              placeholder-stone-400 dark:placeholder-stone-500 transition-shadow"
            rows={3}
            disabled={isSubmitting}
          />
          <div className="flex items-center justify-between mt-2.5">
            <div className="flex items-center gap-3 text-xs text-stone-400 dark:text-stone-500">
              <span className="hidden sm:inline">Ctrl / Cmd + Enter 提交</span>
              {draftSaved && (
                <span className="inline-flex items-center gap-1 text-emerald-600 dark:text-emerald-400">
                  <span className="w-1.5 h-1.5 rounded-full bg-emerald-500" />
                  草稿已暂存
                </span>
              )}
            </div>
            <div className="flex items-center gap-2.5">
              <button
                onClick={() => onShowCompleteConfirm(true)}
                disabled={isSubmitting}
                className="px-4 py-2 text-sm font-medium rounded-lg text-stone-500 dark:text-stone-400
                  hover:text-amber-600 dark:hover:text-amber-400 hover:bg-amber-50 dark:hover:bg-amber-900/20
                  transition-colors disabled:opacity-50 disabled:cursor-not-allowed inline-flex items-center gap-1.5"
              >
                <Flag className="w-3.5 h-3.5" />
                提前交卷
              </button>
              <motion.button
                onClick={onSubmit}
                disabled={!answer.trim() || isSubmitting}
                className="px-6 py-2 btn-primary text-sm disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:scale-100 inline-flex items-center gap-2"
                whileTap={{ scale: isSubmitting || !answer.trim() ? 1 : 0.97 }}
              >
                <LoadingButtonContent loading={isSubmitting} loadingText="提交中">
                  <Send className="w-4 h-4" />
                  提交回答
                </LoadingButtonContent>
              </motion.button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
