import { afterEach, describe, expect, it, vi } from 'vitest';
import { ragChatApi, type RagCitationMetadata, type RagRouteResult } from './ragChat';

describe('ragChatApi citation stream event', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('将 citation 元数据分流且不写入正文 token', async () => {
    const metadata: RagCitationMetadata = {
      sources: [{
        knowledgeBaseId: 1,
        documentTitle: 'RAG.md',
        sourceName: 'RAG.md',
        category: null,
        sectionTitle: null,
        chunkIndex: null,
        chunkCount: null,
        snippet: '引用片段',
        similarity: 0.9,
        cited: true,
      }],
      confidence: 0.85,
      invalidCitations: [9],
    };
    const stream = `data:回答 [1]\n\ndata:citation:${JSON.stringify(metadata)}\n\n`;
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(stream, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    })));
    const tokens: string[] = [];
    const citations: RagCitationMetadata[] = [];

    await ragChatApi.sendMessageStream(
      1,
      '问题',
      chunk => tokens.push(chunk),
      vi.fn(),
      vi.fn(),
      undefined,
      undefined,
      value => citations.push(value),
    );

    expect(tokens).toEqual(['回答 [1]']);
    expect(citations).toEqual([metadata]);
  });

  it('将 route 元数据分流且不写入正文 token', async () => {
    const route: RagRouteResult = {
      source: 'relational_db',
      intent: '结构化记录查询',
      confidence: 0.88,
      reasoning: '命中面试记录关键词',
    };
    const stream = `data:结果

data:route:${JSON.stringify(route)}

`;
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(stream, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    })));
    const tokens: string[] = [];
    const routes: RagRouteResult[] = [];

    await ragChatApi.sendMessageStream(
      1,
      '查询记录',
      chunk => tokens.push(chunk),
      vi.fn(),
      vi.fn(),
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      undefined,
      value => routes.push(value),
    );

    expect(tokens).toEqual(['结果']);
    expect(routes).toEqual([route]);
  });

  it('解析 v1 structured envelope，并在 done + EOF 时只完成一次', async () => {
    const envelope = (sequence: number, event: string, payload: unknown) => JSON.stringify({
      traceId: 'trace-1',
      sequence,
      stage: event === 'token' ? 'generation' : event,
      event,
      timestamp: '2026-08-09T00:00:00Z',
      payload,
    });
    const stream = [
      `event: token\ndata: ${envelope(1, 'token', '第一行\n')}\n\n`,
      `event: citation\ndata: ${envelope(2, 'citation', { ...({ confidence: 0.9, sources: [], invalidCitations: [] }) })}\n\n`,
      `event: done\ndata: ${envelope(3, 'done', '完成')}\n\n`,
    ].join('');
    const fetchMock = vi.fn().mockResolvedValue(new Response(stream, {
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
    }));
    vi.stubGlobal('fetch', fetchMock);
    const tokens: string[] = [];
    const citations: RagCitationMetadata[] = [];
    const complete = vi.fn();

    await ragChatApi.sendMessageStream(
      1,
      '问题',
      chunk => tokens.push(chunk),
      complete,
      vi.fn(),
      undefined,
      undefined,
      value => citations.push(value),
    );

    expect(tokens).toEqual(['第一行\n']);
    expect(citations).toEqual([{ confidence: 0.9, sources: [], invalidCitations: [] }]);
    expect(complete).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][1]?.headers).toMatchObject({ 'X-SSE-Protocol': 'v1' });
  });
});
