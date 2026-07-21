import { afterEach, describe, expect, it, vi } from 'vitest';
import { ragChatApi, type RagCitationMetadata } from './ragChat';

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
});
