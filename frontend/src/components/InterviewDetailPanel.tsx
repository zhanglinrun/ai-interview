import {useState} from 'react';
import {
  AlertCircle,
  BarChart3,
  CheckCircle,
  ChevronDown,
  CirclePlus,
  MessageSquare,
} from 'lucide-react';
import type {AnswerItem, InterviewDetail} from '../api/history';
import {getScoreTextColor} from '../utils/score';
import {
  type EvaluationStatus,
  hasReliableEvaluationScore,
  isDegradedEvaluationFeedback,
  isEvaluationFailed,
} from '../utils/interviewStatus';

interface InterviewDetailPanelProps {
  interview: InterviewDetail;
  onReevaluate?: () => void;
  reevaluating?: boolean;
}

export default function InterviewDetailPanel({
  interview,
  onReevaluate,
  reevaluating = false,
}: InterviewDetailPanelProps) {
  const [expandedQuestions, setExpandedQuestions] = useState<Set<number>>(
    () => new Set((interview.answers || []).map((_, index) => index)),
  );

  const toggleQuestion = (index: number) => {
    setExpandedQuestions(previous => {
      const next = new Set(previous);
      if (next.has(index)) {
        next.delete(index);
      } else {
        next.add(index);
      }
      return next;
    });
  };

  return (
    <div className="space-y-5">
      <ScoreCard
        score={interview.overallScore}
        feedback={interview.overallFeedback}
        evaluateStatus={interview.evaluateStatus}
        onReevaluate={onReevaluate}
        reevaluating={reevaluating}
      />

      {interview.strengths && interview.strengths.length > 0 && (
        <StrengthsSection strengths={interview.strengths} />
      )}

      {interview.improvements && interview.improvements.length > 0 && (
        <ImprovementsSection improvements={interview.improvements} />
      )}

      <QuestionsSection
        answers={interview.answers || []}
        expandedQuestions={expandedQuestions}
        toggleQuestion={toggleQuestion}
      />
    </div>
  );
}

function ScoreCard({
  score,
  feedback,
  evaluateStatus,
  onReevaluate,
  reevaluating,
}: {
  score: number | null;
  feedback: string | null;
  evaluateStatus?: EvaluationStatus;
  onReevaluate?: () => void;
  reevaluating?: boolean;
}) {
  const failed = isEvaluationFailed(evaluateStatus) || isDegradedEvaluationFeedback(feedback);
  const reliable = hasReliableEvaluationScore({
    evaluateStatus,
    overallScore: score,
    overallFeedback: feedback,
  });
  const circumference = 2 * Math.PI * 42;
  const displayScore = reliable ? score : null;
  const safeScore = displayScore === null ? 0 : Math.max(0, Math.min(100, displayScore));
  const strokeDashoffset = circumference - (safeScore / 100) * circumference;

  return (
    <section className="surface-card flex flex-col gap-5 p-5 sm:flex-row sm:items-center">
      <div className="relative mx-auto h-24 w-24 flex-shrink-0 sm:mx-0">
        <svg className="h-24 w-24 -rotate-90" viewBox="0 0 96 96" aria-hidden="true">
          <circle
            cx="48"
            cy="48"
            r="42"
            fill="none"
            strokeWidth="7"
            className="stroke-stone-200 dark:stroke-stone-700"
          />
          <circle
            cx="48"
            cy="48"
            r="42"
            fill="none"
            strokeWidth="7"
            strokeLinecap="round"
            strokeDasharray={circumference}
            strokeDashoffset={strokeDashoffset}
            className="stroke-primary-600"
          />
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center">
          <span className="text-2xl font-semibold text-stone-900 dark:text-white">{displayScore ?? '-'}</span>
          <span className="text-xs text-stone-500 dark:text-stone-400">总分</span>
        </div>
      </div>

      <div className="min-w-0 text-center sm:text-left">
        <h3 className="text-lg font-semibold text-stone-900 dark:text-white">
          {failed ? '评估失败' : '面试反馈'}
        </h3>
        <p className="mt-2 max-w-3xl text-sm leading-6 text-stone-600 dark:text-stone-300">
          {failed
            ? (feedback || '评估未生成有效分数，请重新评估。')
            : (feedback || (score === null ? '本次尚未完成评估，暂时没有反馈。' : '本次暂无文字反馈。'))}
        </p>
        {failed && onReevaluate && (
          <button
            type="button"
            onClick={onReevaluate}
            disabled={reevaluating}
            className="btn-primary mt-3 px-4 py-2 text-sm disabled:opacity-50"
          >
            {reevaluating ? '重新评估中…' : '重新评估'}
          </button>
        )}
      </div>
    </section>
  );
}

function StrengthsSection({strengths}: {strengths: string[]}) {
  return (
    <section className="surface-card p-5">
      <h4 className="mb-3 flex items-center gap-2 font-semibold text-emerald-700 dark:text-emerald-400">
        <CheckCircle className="h-5 w-5" />
        做得较好
      </h4>
      <ul className="space-y-2">
        {strengths.map((strength, index) => (
          <li key={index} className="flex items-start gap-3 text-sm leading-6 text-stone-700 dark:text-stone-300">
            <span className="mt-2 h-1.5 w-1.5 flex-shrink-0 rounded-full bg-emerald-500" />
            <span>{strength}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function ImprovementsSection({improvements}: {improvements: string[]}) {
  return (
    <section className="surface-card p-5">
      <h4 className="mb-3 flex items-center gap-2 font-semibold text-amber-700 dark:text-amber-400">
        <AlertCircle className="h-5 w-5" />
        回答建议
      </h4>
      <ul className="space-y-2">
        {improvements.map((improvement, index) => (
          <li key={index} className="flex items-start gap-3 text-sm leading-6 text-stone-700 dark:text-stone-300">
            <span className="mt-2 h-1.5 w-1.5 flex-shrink-0 rounded-full bg-amber-500" />
            <span>{improvement}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function QuestionsSection({
  answers,
  expandedQuestions,
  toggleQuestion,
}: {
  answers: AnswerItem[];
  expandedQuestions: Set<number>;
  toggleQuestion: (index: number) => void;
}) {
  return (
    <section>
      <h4 className="mb-3 flex items-center gap-2 font-semibold text-stone-800 dark:text-white">
        <MessageSquare className="h-5 w-5 text-primary-600" />
        逐题回顾
      </h4>

      {answers.length === 0 ? (
        <div className="surface-card p-5 text-sm text-stone-500 dark:text-stone-400">暂无作答记录。</div>
      ) : (
        <div className="space-y-3">
          {answers.map((answer, index) => (
            <QuestionCard
              key={`${answer.questionIndex}-${index}`}
              answer={answer}
              isExpanded={expandedQuestions.has(index)}
              onToggle={() => toggleQuestion(index)}
            />
          ))}
        </div>
      )}
    </section>
  );
}

function QuestionCard({
  answer,
  isExpanded,
  onToggle,
}: {
  answer: AnswerItem;
  isExpanded: boolean;
  onToggle: () => void;
}) {
  return (
    <article className="surface-card overflow-hidden">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={isExpanded}
        className="flex w-full flex-wrap items-center justify-between gap-3 px-5 py-4 text-left transition-colors hover:bg-stone-50 dark:hover:bg-stone-800/60"
      >
        <span className="flex min-w-0 flex-wrap items-center gap-2.5">
          <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-stone-100 text-sm font-semibold text-stone-600 dark:bg-stone-800 dark:text-stone-300">
            {answer.questionIndex + 1}
          </span>
          <span className="status-badge bg-primary-50 text-primary-700 dark:bg-primary-950/50 dark:text-primary-300">
            {answer.category || '综合'}
          </span>
          <span className={`text-sm font-semibold ${
            answer.score == null || isDegradedEvaluationFeedback(answer.feedback)
              ? 'text-stone-400'
              : getScoreTextColor(answer.score, [80, 60])
          }`}>
            {answer.score == null || isDegradedEvaluationFeedback(answer.feedback)
              ? '未评'
              : `${answer.score} 分`}
          </span>
        </span>
        <ChevronDown className={`h-5 w-5 flex-shrink-0 text-stone-400 transition-transform ${isExpanded ? 'rotate-180' : ''}`} />
      </button>

      <div className="border-t border-stone-100 px-5 py-4 dark:border-stone-800">
        <p className="font-medium leading-7 text-stone-900 dark:text-white">{answer.question}</p>
      </div>

      {isExpanded && (
        <div className="space-y-4 border-t border-stone-100 px-5 py-4 dark:border-stone-800">
          <div className="rounded-lg bg-stone-50 p-4 dark:bg-stone-800/60">
            <p className="mb-2 flex items-center gap-1.5 text-sm font-medium text-stone-500 dark:text-stone-400">
              <MessageSquare className="h-4 w-4" />
              你的回答
            </p>
            <p className={`whitespace-pre-line text-sm leading-6 ${
              !answer.userAnswer || answer.userAnswer === '不知道'
                ? 'font-medium text-red-600 dark:text-red-400'
                : 'text-stone-700 dark:text-stone-300'
            }`}>
              {answer.userAnswer || '未回答'}
            </p>
          </div>

          {answer.feedback && (
            <div>
              <p className="mb-2 flex items-center gap-2 text-sm font-medium text-stone-600 dark:text-stone-400">
                <BarChart3 className="h-4 w-4 text-primary-600" />
                回答反馈
              </p>
              <p className="text-sm leading-6 text-stone-700 dark:text-stone-300">{answer.feedback}</p>
            </div>
          )}

          {answer.referenceAnswer && (
            <div className="rounded-lg border border-stone-200 bg-stone-50 p-4 dark:border-stone-700 dark:bg-stone-800/60">
              <p className="mb-2 flex items-center gap-2 text-sm font-medium text-stone-600 dark:text-stone-400">
                <CirclePlus className="h-4 w-4 text-primary-600" />
                参考思路
              </p>
              <div className="whitespace-pre-line text-sm leading-6 text-stone-700 dark:text-stone-300">
                {answer.referenceAnswer}
              </div>
            </div>
          )}
        </div>
      )}
    </article>
  );
}
