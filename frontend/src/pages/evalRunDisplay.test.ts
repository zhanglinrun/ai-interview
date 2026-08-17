import { describe, expect, it } from 'vitest';
import type { EvalRunResponse } from '../api/eval';
import {
  buildStandardRagMetrics,
  layersForScope,
  firstAttentionGate,
  formatEvalReport,
  gateTone,
  humanizeFailure,
  intentGateValue,
  intentLabel,
  metricLabel,
  pickDefaultEvalKbIds,
  qualityGateSummary,
  toRelatedOnlyIntentCase,
} from './evalRunDisplay';

function result(partial: Partial<EvalRunResponse>): EvalRunResponse {
  return {
    runId: 'eval-1',
    title: 'Agent Demo',
    baselineKey: 'agent-demo-baseline',
    baseline: false,
    overallScore: 0.9,
    regression: false,
    intent: null,
    rag: null,
    judge: null,
    baselineComparison: null,
    qualityGate: { passed: true, metrics: {}, thresholds: {}, failures: [] },
    createdAt: '2026-08-16T00:00:00',
    ...partial,
  };
}

describe('evalRunDisplay', () => {
  it('默认勾面渣 Redis / JVM，不勾简历', () => {
    expect(pickDefaultEvalKbIds([
      { id: 1, name: 'RAG Smoke Test', originalFilename: 'sample-resume.md', category: 'java' },
      { id: 46, name: '面渣逆袭 JVM篇 V2.1.pdf', originalFilename: '面渣逆袭 JVM篇 V2.1.pdf', category: '后端八股' },
      { id: 49, name: '面渣逆袭Redis篇V2.0.pdf', originalFilename: '面渣逆袭Redis篇V2.0.pdf', category: '后端八股' },
    ])).toEqual([49, 46]);
    expect(pickDefaultEvalKbIds([
      { id: 1, name: 'RAG Smoke Test', originalFilename: 'sample-resume.md' },
    ])).toEqual([]);
  });

  it('可以只评一层，不必三层一起交', () => {
    expect(layersForScope('retrieve')).toEqual({ intent: false, retrieve: true, judge: false });
    expect(layersForScope('all')).toEqual({ intent: true, retrieve: true, judge: true });
  });

  it('意图门只区分查资料和闲聊，提交时不锁定 TECH_KB 细类', () => {
    expect(intentLabel('TECH_KB')).toBe('查资料');
    expect(intentLabel('CODE_REVIEW')).toBe('查资料');
    expect(intentLabel('CAREER')).toBe('查资料');
    expect(intentLabel('OFF_TOPIC')).toBe('闲聊');
    expect(intentGateValue('TECH_KB', true)).toBe('RELATED');
    expect(toRelatedOnlyIntentCase({
      question: 'JVM',
      expectedIntent: 'TECH_KB',
      expectedRelated: true,
    })).toEqual({ question: 'JVM', expectedRelated: true });
    expect(metricLabel('intentAccuracy')).toBe('问题分类准确率');
    expect(metricLabel('retrievalRecall')).toBe('Context Recall');
    expect(metricLabel('retrievalPrecision')).toBe('Context Precision');
    expect(metricLabel('groundedness')).toBe('Faithfulness');
    expect(humanizeFailure('intentAccuracy=0.5 < 0.8')).toBe('问题分类准确率 50.0%，低于门槛 80.0%');
  });

  it('把接口字段映射到标准五指标，缺层就标未跑', () => {
    const empty = buildStandardRagMetrics(result({}));
    expect(empty.map(item => item.name)).toEqual([
      'Context Precision',
      'Context Recall',
      'Faithfulness',
      'Answer Relevancy',
      'Answer Correctness',
    ]);
    expect(empty.every(item => item.value == null)).toBe(true);

    const mapped = buildStandardRagMetrics(result({
      rag: {
        runId: 'rag-1',
        total: 2,
        k: 5,
        hitRate: 1,
        mrr: 1,
        ndcg: 1,
        retrievalRecall: 0.8,
        retrievalPrecision: 0.6,
        items: [],
      },
      judge: {
        total: 1,
        passed: 1,
        passRate: 1,
        averageOverall: 0.77,
        averageRelevance: 0.88,
        averageAccuracy: 0.91,
        averageCompleteness: 0.7,
        averageHelpfulness: 0.7,
        items: [],
      },
    }));
    expect(mapped.find(item => item.id === 'context_precision')?.value).toBe(0.6);
    expect(mapped.find(item => item.id === 'context_recall')?.value).toBe(0.8);
    expect(mapped.find(item => item.id === 'faithfulness')?.value).toBe(0.91);
    expect(mapped.find(item => item.id === 'answer_relevancy')?.value).toBe(0.88);
    expect(mapped.find(item => item.id === 'answer_correctness')?.value).toBe(0.77);
  });

  it('按失败顺序指向第一道需要看的门', () => {
    expect(firstAttentionGate(result({
      intent: {
        total: 2,
        correct: 1,
        accuracy: 0.5,
        macroF1: 0.5,
        items: [
          {
            question: 'JVM',
            expectedIntent: 'TECH_KB',
            expectedRelated: true,
            actualIntent: 'TECH_KB',
            actualRelated: true,
            confidence: 0.9,
            correct: true,
            reason: '',
          },
          {
            question: '天气',
            expectedIntent: 'OFF_TOPIC',
            expectedRelated: false,
            actualIntent: 'TECH_KB',
            actualRelated: true,
            confidence: 0.4,
            correct: false,
            reason: '',
          },
        ],
      },
    }))).toBe('intent');
  });

  it('没有知识库时检索门是警告而不是跳过', () => {
    expect(gateTone('retrieve', result({}), { hasRetrieveCases: true, hasKnowledgeBase: false })).toBe('warn');
    expect(gateTone('intent', null, { hasRetrieveCases: true, hasKnowledgeBase: true })).toBe('skip');
  });

  it('检索全命中但质量门未过时检索层仍标失败', () => {
    expect(gateTone('retrieve', result({
      rag: {
        runId: 'rag-1',
        total: 1,
        k: 5,
        hitRate: 1,
        mrr: 0.5,
        ndcg: 0.4,
        retrievalRecall: 0.5,
        retrievalPrecision: 0.33,
        items: [{
          question: '什么是缓存穿透，如何防止',
          hit: true,
          firstHitRank: 2,
          reciprocalRank: 0.5,
          ndcg: 0.4,
          retrievalRecall: 0.5,
          retrievalPrecision: 0.33,
        }],
      },
      qualityGate: {
        passed: false,
        metrics: { retrievalRecall: 0.5 },
        thresholds: { retrievalRecall: 0.85 },
        failures: ['retrievalRecall=0.5 < 0.85'],
      },
    }), { hasRetrieveCases: true, hasKnowledgeBase: true })).toBe('fail');
  });

  it('质量门文案说明门槛来自后端，报告不再指向已搬走的旧评测目录', () => {
    const failed = qualityGateSummary({
      passed: false,
      metrics: { intentAccuracy: 0.5 },
      thresholds: { intentAccuracy: 0.8 },
      failures: ['intentAccuracy=0.5 < 0.8'],
    });
    expect(failed.title).toContain('低于门槛');
    expect(failed.detail).toContain('问题分类准确率');

    const report = formatEvalReport(result({
      intent: {
        total: 1,
        correct: 1,
        accuracy: 1,
        macroF1: 1,
        items: [{
          question: '讲讲 JVM 垃圾回收原理',
          expectedIntent: 'TECH_KB',
          expectedRelated: true,
          actualIntent: 'TECH_KB',
          actualRelated: true,
          confidence: 0.9,
          correct: true,
          reason: '',
        }],
      },
    }));
    expect(report).toContain('标准 RAG 五指标');
    expect(report).toContain('问题 q');
    expect(report).toContain('eval/rag/README.md');
    expect(report).not.toContain('critic-badcase');
    expect(report).not.toContain('eval/ragas');
  });

  it('空跑的意图和生成层不写进报告，检索项写出命中词和片段', () => {
    const report = formatEvalReport(result({
      intent: {
        total: 0,
        correct: 0,
        accuracy: 0,
        macroF1: 0,
        items: [],
      },
      judge: {
        total: 0,
        passed: 0,
        passRate: 0,
        averageOverall: 0,
        averageRelevance: 0,
        averageAccuracy: 0,
        averageCompleteness: 0,
        averageHelpfulness: 0,
        items: [],
      },
      rag: {
        runId: 'rag-1',
        total: 1,
        k: 5,
        hitRate: 1,
        mrr: 0.5,
        ndcg: 0.4,
        retrievalRecall: 0.5,
        retrievalPrecision: 0.33,
        items: [{
          question: '什么是缓存穿透，如何防止',
          hit: true,
          firstHitRank: 2,
          reciprocalRank: 0.5,
          ndcg: 0.4,
          retrievalRecall: 0.5,
          retrievalPrecision: 0.33,
          matchedKeywords: ['不存在'],
          missingKeywords: ['布隆过滤器|bloom'],
          retrievedSegments: [
            { rank: 1, snippet: '缓存击穿是热点 key 过期' },
            { rank: 2, snippet: '缓存穿透是查询不存在的数据' },
          ],
        }],
      },
    }));
    expect(report).not.toContain('该不该检索');
    expect(report).not.toContain('生成答案 a');
    expect(report).toContain('命中词 不存在');
    expect(report).toContain('缺 布隆过滤器|bloom');
    expect(report).not.toContain('未命中期望词');
    expect(report).toContain('缓存穿透是查询不存在的数据');
  });
});
