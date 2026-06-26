package com.linrun.interview.modules.knowledgebase.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import com.linrun.interview.modules.knowledgebase.service.splitter.MarkdownHeaderBrotherTextSplitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库分片服务（LangChain4j 版，对齐 know-engine）。
 *
 * <p>用 {@link MarkdownHeaderBrotherTextSplitter}（兄弟切片）按 Markdown 标题层级分段，
 * 超出 chunkSize 的章节二次切割成同组兄弟 chunk，共享 brotherChunkId 并记录顺序，
 * 检索时可按序拼接。替代原 Spring AI TokenTextSplitter + 5 策略体系（hybrid/recursive/semantic/auto），
 * 简化对齐 know-engine 的切片实现。
 */
@Slf4j
@Service
public class KnowledgeBaseChunkingService {

    /**
     * chunk 最短有效长度（trim 后字符数）。低于此值的 chunk 视为噪声，
     * 入库只会污染检索结果并浪费 embedding 调用，统一在此过滤。
     */
    private static final int MIN_CHUNK_CHARS = 5;

    private final MarkdownHeaderBrotherTextSplitter splitter;
    private final int chunkSizeChars;
    private final int overlapChars;

    @Autowired
    public KnowledgeBaseChunkingService(KnowledgeBaseQueryProperties queryProperties) {
        KnowledgeBaseQueryProperties properties = queryProperties == null
            ? new KnowledgeBaseQueryProperties()
            : queryProperties;
        this.chunkSizeChars = Math.max(64, properties.getChunkSizeChars());
        this.overlapChars = Math.max(0, properties.getChunkOverlapChars());
        this.splitter = new MarkdownHeaderBrotherTextSplitter(chunkSizeChars, overlapChars);
    }

    public List<TextSegment> split(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<TextSegment> rawChunks = splitter.split(Document.from(content));
        List<TextSegment> effectiveChunks = new ArrayList<>(rawChunks.size());
        int filtered = 0;
        for (TextSegment chunk : rawChunks) {
            String text = chunk.text() == null ? "" : chunk.text().trim();
            if (text.length() < MIN_CHUNK_CHARS) {
                filtered++;
                continue;
            }
            effectiveChunks.add(chunk);
        }
        log.info("分片完成: strategy=markdown-brother, chunkSize={}, overlap={}, raw={}, filtered={}, effective={}",
            chunkSizeChars, overlapChars, rawChunks.size(), filtered, effectiveChunks.size());
        return effectiveChunks;
    }
}
