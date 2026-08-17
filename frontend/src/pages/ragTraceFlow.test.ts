import { describe, expect, it } from 'vitest';
import type { RagTraceDetail, RagTraceRun, RagTraceStage } from '../api/ragTrace';
import {
  buildRagTraceTree,
  flattenObservations,
  formatLatency,
  hasProcessSpans,
  inferQueryKind,
  intentMeaning,
  labelIntent,
  labelSource,
  looksLikeNavJunk,
  observationTitle,
  observationTypeLabel,
  parseIntentOutput,
  waterfallShare,
} from './ragTraceFlow';

function run(partial: Partial<RagTraceRun> = {}): RagTraceRun {
  return {
    id: 1,
    traceId: 'rag-trace-1',
    userId: 1,
    sessionId: 's1',
    question: 'RocketMQ 是什么',
    status: 'COMPLETED',
    routeSource: 'knowledge_base',
    routeIntent: 'TECH_KB',
    latencyMs: 2300,
    answerSummary: null,
    createdAt: '2026-08-16T07:00:00Z',
    completedAt: '2026-08-16T07:00:02Z',
    ...partial,
  };
}

function detail(partial: Partial<RagTraceDetail> = {}): RagTraceDetail {
  return {
    run: run(),
    stages: [
      {
        id: 1,
        ragRunId: 'rag-1',
        stage: 'INTENT',
        status: 'route',
        dataSource: 'knowledge_base',
        inputSummary: 'TECH_KB',
        outputSummary: '内容围绕 RocketMQ 架构',
        metadataJson: null,
        provider: null,
        modelName: null,
        inputTokens: null,
        outputTokens: null,
        filterJson: null,
        fallbackReason: null,
        startedAt: '2026-08-16T07:00:00Z',
        completedAt: '2026-08-16T07:00:00Z',
        latencyMs: 0,
      },
      {
        id: 2,
        ragRunId: 'rag-1',
        stage: 'REWRITE',
        status: 'rewritten',
        dataSource: null,
        inputSummary: 'RocketMQ 是什么、核心原理、应用场景及面试常考点',
        outputSummary: '[]',
        metadataJson: null,
        provider: null,
        modelName: null,
        inputTokens: null,
        outputTokens: null,
        filterJson: null,
        fallbackReason: null,
        startedAt: '2026-08-16T07:00:00Z',
        completedAt: '2026-08-16T07:00:00Z',
        latencyMs: 0,
      },
    ],
    candidates: [
      {
        id: 11,
        ragRunId: 'rag-1',
        stage: 'RETRIEVAL',
        rankNo: 2,
        sourceType: 'knowledge_base',
        documentId: '1',
        segmentId: 'c2',
        evidenceId: null,
        score: 2.58,
        rerankScore: null,
        snippet: 'https://tobebetterjavaer.com/sidebar/sanfene/jvm.html https://javabetter.cn/sidebar/sanfene/spring.html',
        metadataJson: null,
        permissionAllowed: true,
        versionMatched: true,
        filterReason: null,
      },
      {
        id: 12,
        ragRunId: 'rag-1',
        stage: 'RERANK',
        rankNo: 1,
        sourceType: 'knowledge_base',
        documentId: '1',
        segmentId: 'c1',
        evidenceId: null,
        score: 3.29,
        rerankScore: 3.29,
        snippet: '消息队列主要有三大用途：解耦、异步、削峰。',
        metadataJson: null,
        permissionAllowed: true,
        versionMatched: true,
        filterReason: null,
      },
    ],
    citations: [
      { id: 21, ragRunId: 'rag-1', citationIndex: 1, evidenceId: '1', sourceLocator: '面渣逆袭RocketMQ篇.pdf', cited: true, valid: true, confidence: 1 },
      { id: 22, ragRunId: 'rag-1', citationIndex: 2, evidenceId: '1', sourceLocator: '面渣逆袭RocketMQ篇.pdf', cited: false, valid: true, confidence: 1 },
    ],
    answer: {
      id: 31,
      ragRunId: 'rag-1',
      answer: 'RocketMQ 是分布式消息队列。',
      groundedStatus: 'pass',
      confidence: 1,
      invalidCitationsJson: null,
      tokenCount: 18,
      createdAt: '2026-08-16T07:00:02Z',
    },
    ...partial,
  };
}

describe('rag trace labels', () => {
  it('keeps engineer-facing latency and domain labels', () => {
    expect(labelIntent('TECH_KB')).toBe('查资料');
    expect(labelIntent('CODE_REVIEW')).toBe('查资料');
    expect(intentMeaning('TECH_KB')).toBe('查资料');
    expect(labelIntent('OFF_TOPIC')).toBe('闲聊');
    expect(labelIntent('TECH_KB', false)).toBe('闲聊');
    expect(labelSource('knowledge_base')).toBe('知识库文档');
    expect(formatLatency(2300)).toBe('2.3 秒');
    expect(observationTitle('rag_pipeline')).toBe('整次问答');
    expect(observationTitle('retrieve')).toBe('检索');
    expect(observationTitle('retrieve-2')).toBe('检索');
    expect(observationTitle('retrieval')).toBe('检索');
    expect(observationTypeLabel('retriever')).toBe('检索');
    expect(looksLikeNavJunk('https://a.com/sidebar/x https://javabetter.cn/sidebar/y')).toBe(true);
  });

  it('parses fusion intent output into inspector fields', () => {
    const fields = parseIntentOutput(
      'TECH_KB related=true 三路融合判定为 TECH_KB，综合置信度 0.9397；llm=TECH_KB/0.95, vector=TECH_KB/0.2761, rule=OFF_TOPIC/0.0',
    );
    expect(fields).toEqual([
      { label: '判定', value: '查资料' },
      { label: '是否查知识库', value: '是' },
      { label: '综合置信度', value: '94%' },
      { label: 'LLM', value: '查资料 · 95%' },
      { label: '向量', value: '查资料 · 28%' },
      { label: '规则', value: '闲聊 · 0%' },
    ]);
  });

  it('names retrieve queries by comparing input to the original and rewrite', () => {
    expect(inferQueryKind('RocketMQ 是什么', 'RocketMQ 是什么', 'RocketMQ 核心原理', 1, 2)).toBe('原问题');
    expect(inferQueryKind('RocketMQ 核心原理', 'RocketMQ 是什么', 'RocketMQ 核心原理', 2, 2)).toBe('改写查询');
    expect(inferQueryKind('A'.repeat(90), 'RocketMQ 是什么', 'RocketMQ 核心原理', 3, 3)).toBe('HyDE 假设文档');
    expect(inferQueryKind('Broker 角色', 'RocketMQ 是什么', 'RocketMQ 核心原理', 2, 3)).toBe('子查询 2');
  });

  it('keeps a visible waterfall bar for short steps', () => {
    expect(waterfallShare(0, 12, 16800).width).toBeGreaterThan(0);
    expect(waterfallShare(0, 0, 16800).width).toBe(0);
  });
});

describe('buildRagTraceTree', () => {
  it('nests retrieve/rerank under a retrieval parent instead of a flat list', () => {
    const tree = buildRagTraceTree(detail());
    expect(tree.root).toMatchObject({ id: 'root', type: 'chain', name: 'rag_pipeline' });
    expect(tree.root.input).toBe('RocketMQ 是什么');
    expect(tree.root.output).toContain('分布式消息队列');
    expect(tree.children.map(child => `${child.type}:${child.name}`)).toEqual([
      'span:intent',
      'span:rewrite',
      'retriever:retrieval',
      'generation:generate',
      'span:citation',
    ]);
    expect(tree.children.find(child => child.name === 'retrieval')?.children.map(child => `${child.type}:${child.name}`)).toEqual([
      'span:route',
      'retriever:retrieve',
      'retriever:rerank',
    ]);
  });

  it('renders retriever documents with scores and nav-junk flags', () => {
    const tree = buildRagTraceTree(detail());
    const nodes = flattenObservations(tree.children);
    const retrieve = nodes.find(child => child.name === 'retrieve');
    expect(retrieve?.documents[0].junk).toBe(true);
    expect(retrieve?.documents[0].score).toBe(2.58);
    const rerank = nodes.find(child => child.name === 'rerank');
    expect(rerank?.documents[0].cited).toBe(true);
  });

  it('omits steps that never ran instead of padding an 8-card flowchart', () => {
    const tree = buildRagTraceTree(detail({
      stages: [],
      candidates: [],
      citations: [],
      answer: null,
      run: run({ routeIntent: null, routeSource: null }),
    }));
    expect(tree.children).toEqual([]);
    expect(tree.root.output).toContain('无回答快照');
  });

  it('marks citation observation failed when an index is invented', () => {
    const tree = buildRagTraceTree(detail({
      citations: [
        { id: 21, ragRunId: 'rag-1', citationIndex: 9, evidenceId: null, sourceLocator: 'x', cited: true, valid: false, confidence: 0.2 },
      ],
    }));
    expect(flattenObservations(tree.children).find(child => child.name === 'citation')?.status).toBe('fail');
    expect(tree.root.status).toBe('fail');
  });

  it('keeps snapshot reconstruction when stages have no process timing', () => {
    const tree = buildRagTraceTree(detail());
    expect(hasProcessSpans(detail().stages)).toBe(false);
    expect(tree.note).toContain('旧记录没有逐步耗时');
    expect(flattenObservations(tree.children).find(child => child.name === 'retrieve')?.latencyLabel).toBe('—');
  });

  it('builds a nested timed tree with distinguishable retrieve titles and intent fields', () => {
    const hyde = `假设用户在问 Redis 为什么快。文档应覆盖单线程、IO 多路复用、纯内存、高效数据结构。${'x'.repeat(40)}`;
    const tree = buildRagTraceTree(detail({
      stages: [
        timedStage(1, 'INTENT', 12, 'RocketMQ 是什么',
          'TECH_KB related=true 三路融合判定为 TECH_KB，综合置信度 0.9397；llm=TECH_KB/0.95, vector=TECH_KB/0.2761, rule=OFF_TOPIC/0.0',
          'span'),
        timedStage(2, 'REWRITE', 80, 'RocketMQ 是什么', 'RocketMQ 核心原理与面试考点', 'span'),
        timedStage(3, 'ROUTE', 5, 'RocketMQ 核心原理与面试考点', 'knowledge_base TECH_KB', 'span', 'knowledge_base'),
        timedStage(4, 'RETRIEVAL', 120, 'RocketMQ 核心原理与面试考点', '2 docs', 'retriever'),
        timedStage(5, 'RETRIEVAL', 90, hyde, '1 docs', 'retriever'),
        timedStage(6, 'RERANK', 40, 'RocketMQ 核心原理与面试考点', '1 docs', 'retriever'),
        timedStage(7, 'GENERATE', 900, 'RocketMQ 是什么', 'RocketMQ 是分布式消息队列。', 'generation'),
        timedStage(8, 'CITATION', 3, 'RocketMQ 是分布式消息队列。', 'cited=1 invalid=0', 'span'),
      ],
    }));
    expect(tree.note).toContain('多路');
    expect(tree.children.map(child => child.name)).toEqual([
      'intent',
      'rewrite',
      'retrieval',
      'generate',
      'citation',
    ]);
    const retrieval = tree.children.find(child => child.name === 'retrieval');
    expect(retrieval?.children.map(child => `${child.name}:${child.title}:${child.latencyLabel}`)).toEqual([
      'route:路由 · 改写查询:5 毫秒',
      'retrieve:检索 · 改写查询:120 毫秒',
      'retrieve-2:检索 · HyDE 假设文档:90 毫秒',
      'rerank:精排:40 毫秒',
    ]);
    expect(tree.children.find(child => child.name === 'intent')?.title).toBe('意图 · 查资料');
    expect(tree.children.find(child => child.name === 'intent')?.fields).toEqual(expect.arrayContaining([
      { label: '判定', value: '查资料' },
      { label: '是否查知识库', value: '是' },
      { label: '综合置信度', value: '94%' },
    ]));
    expect(retrieval?.children.find(child => child.name === 'retrieve')?.documents).toEqual([]);
    expect(retrieval?.children.find(child => child.name === 'retrieve-2')?.documents[0].junk).toBe(true);
    expect(retrieval?.documents[0].cited).toBe(true);
  });
});

function timedStage(
  id: number,
  stage: string,
  latencyMs: number,
  inputSummary: string,
  outputSummary: string,
  observationType: string,
  dataSource: string | null = null,
): RagTraceStage {
  return {
    id,
    ragRunId: 'rag-1',
    stage,
    status: 'COMPLETED',
    dataSource,
    inputSummary,
    outputSummary,
    metadataJson: JSON.stringify({ observationType }),
    provider: null,
    modelName: null,
    inputTokens: null,
    outputTokens: null,
    filterJson: null,
    fallbackReason: null,
    startedAt: '2026-08-16T07:00:00Z',
    completedAt: '2026-08-16T07:00:01Z',
    latencyMs,
  };
}
