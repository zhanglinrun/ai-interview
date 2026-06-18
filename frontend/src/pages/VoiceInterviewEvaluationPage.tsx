import { useEffect, useState, useRef, useCallback, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, RefreshCw } from 'lucide-react';
import { EvaluationStatusResponse, VoiceEvaluationDetail, voiceInterviewApi } from '../api/voiceInterview';
import InterviewDetailPanel from '../components/InterviewDetailPanel';
import { EmptyState, LoadingState } from '../components/PageState';
import {getErrorMessage} from '../api/request';
import type { EvaluateStatus, InterviewDetail } from '../api/history';
import {
  isEvaluationCompleted,
  isEvaluationFailed,
  isEvaluationProcessing,
} from '../utils/interviewStatus';

export default function VoiceInterviewEvaluationPage() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const sessionNumericId = useMemo(() => {
    if (!sessionId) return null;
    const id = Number(sessionId);
    return Number.isInteger(id) ? id : null;
  }, [sessionId]);
  const [evaluation, setEvaluation] = useState<VoiceEvaluationDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [evaluateStatus, setEvaluateStatus] = useState<EvaluateStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const pollingRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const applyFinalStatus = useCallback((response: EvaluationStatusResponse): boolean => {
    const status = response.evaluateStatus;
    setEvaluateStatus(status);

    if (isEvaluationCompleted(status) && response.evaluation) {
      setEvaluation(response.evaluation);
      setLoading(false);
      return true;
    } else if (isEvaluationFailed(status)) {
      setError(response.evaluateError || '评估生成失败');
      setLoading(false);
      return true;
    }

    return false;
  }, []);

  const startPolling = useCallback(() => {
    if (pollingRef.current) {
      clearTimeout(pollingRef.current);
    }

    pollingRef.current = setTimeout(async () => {
      if (sessionNumericId === null) return;

      try {
        const response = await voiceInterviewApi.getEvaluation(sessionNumericId);
        if (!applyFinalStatus(response)) {
          startPolling();
        }
      } catch (err) {
        setError(getErrorMessage(err, '获取评估状态失败'));
        setLoading(false);
      }
    }, 3000);
  }, [applyFinalStatus, sessionNumericId]);

  const handleStatusResponse = useCallback((response: EvaluationStatusResponse) => {
    if (!applyFinalStatus(response)) {
      startPolling();
    }
  }, [applyFinalStatus, startPolling]);

  const loadEvaluation = useCallback(async () => {
    if (sessionNumericId === null) {
      setError('无效的语音会话 ID');
      setLoading(false);
      return;
    }

    setLoading(true);
    setError(null);

    try {
      const status = await voiceInterviewApi.getEvaluation(sessionNumericId);
      handleStatusResponse(status);
    } catch {
      try {
        const status = await voiceInterviewApi.generateEvaluation(sessionNumericId);
        handleStatusResponse(status);
      } catch (err) {
        console.error('Failed to trigger evaluation:', err);
        setError(getErrorMessage(err, '触发评估失败，请重试'));
        setLoading(false);
      }
    }
  }, [handleStatusResponse, sessionNumericId]);

  useEffect(() => {
    loadEvaluation();
    return () => {
      if (pollingRef.current) {
        clearTimeout(pollingRef.current);
      }
    };
  }, [loadEvaluation]);

  const handleRetry = async () => {
    if (sessionNumericId === null) return;
    setLoading(true);
    setError(null);
    setEvaluateStatus(null);

    try {
      const status = await voiceInterviewApi.generateEvaluation(sessionNumericId);
      handleStatusResponse(status);
    } catch (err) {
      console.error('Failed to retry evaluation:', err);
      setError(getErrorMessage(err, '重试失败，请稍后再试'));
      setLoading(false);
    }
  };

  const interviewDetail = useMemo<InterviewDetail | null>(() => {
    if (!evaluation || !sessionId) return null;
    return {
      id: 0,
      sessionId,
      totalQuestions: evaluation.totalQuestions,
      status: 'COMPLETED',
      overallScore: evaluation.overallScore,
      overallFeedback: evaluation.overallFeedback,
      createdAt: '',
      completedAt: '',
      strengths: evaluation.strengths,
      improvements: evaluation.improvements,
      answers: evaluation.answers.map(a => ({
        questionIndex: a.questionIndex,
        question: a.question,
        category: a.category,
        userAnswer: a.userAnswer,
        score: a.score,
        feedback: a.feedback,
        referenceAnswer: a.referenceAnswer ?? undefined,
        keyPoints: a.keyPoints ?? undefined,
        answeredAt: '',
      })),
    };
  }, [evaluation, sessionId]);

  // Loading state
  if (loading) {
    return (
      <LoadingState
        label={isEvaluationProcessing(evaluateStatus) ? 'AI 正在分析面试表现...' : '正在生成评估报告...'}
        description="预计需要 10-30 秒"
        className="flex flex-col items-center justify-center min-h-[50vh] gap-3"
        spinnerClassName="w-10 h-10 text-primary-500 animate-spin"
        textClassName="text-slate-600 dark:text-slate-300"
      />
    );
  }

  // Error state
  if (error && !evaluation) {
    return (
      <div className="min-h-[50vh] flex items-center justify-center">
        <EmptyState
          title="评估报告生成失败"
          description={error}
          className="text-center"
          titleClassName="text-slate-600 dark:text-slate-300 text-lg mb-2"
          descriptionClassName="text-slate-400 text-sm mb-6"
          action={
          <div className="flex items-center gap-3 justify-center">
            <button
              onClick={handleRetry}
              className="px-6 py-2 bg-primary-500 text-white rounded-lg hover:bg-primary-600 flex items-center gap-2"
            >
              <RefreshCw className="w-4 h-4" />
              重试
            </button>
            <button
              onClick={() => navigate('/interviews')}
              className="px-6 py-2 bg-slate-200 dark:bg-slate-700 text-slate-700 dark:text-slate-300 rounded-lg hover:bg-slate-300 dark:hover:bg-slate-600"
            >
              返回列表
            </button>
          </div>
          }
        />
      </div>
    );
  }

  if (!evaluation || !interviewDetail) {
    return null;
  }

  return (
    <div className="pb-10">
      <div className="max-w-6xl mx-auto">
        <div className="flex items-center gap-3 mb-6">
          <button
            onClick={() => navigate('/interviews')}
            className="p-2 text-slate-400 hover:text-slate-600 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-lg transition-colors"
          >
            <ArrowLeft className="w-5 h-5" />
          </button>
          <div>
            <h1 className="text-xl font-bold text-slate-900 dark:text-white">面试评估报告</h1>
            <p className="text-sm text-slate-500 dark:text-slate-400">语音会话 ID: {sessionId}</p>
          </div>
        </div>
        <InterviewDetailPanel interview={interviewDetail} />
      </div>
    </div>
  );
}
