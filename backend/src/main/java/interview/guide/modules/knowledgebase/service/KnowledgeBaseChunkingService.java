package interview.guide.modules.knowledgebase.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import interview.guide.modules.knowledgebase.service.splitter.MarkdownHeaderBrotherTextSplitter;
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
 *
 * <p>保留文档级质量评估：过滤空白/过短噪声 chunk，产出质量分供向量化前判断。
 */
@Slf4j
@Service
public class KnowledgeBaseChunkingService {

    /**
     * chunk 最短有效长度（trim 后字符数）。低于此值的 chunk 视为噪声，
     * 入库只会污染检索结果并浪费 embedding 调用。
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
        List<TextSegment> chunks = splitter.split(Document.from(content));
        log.info("分片完成: strategy=markdown-brother, chunks={}", chunks.size());
        return chunks;
    }

    /**
     * 带质量评估的分块：在 {@link #split(String)} 基础上过滤空白/过短的噪声 chunk，
     * 并产出文档级质量指标（文档长度、原始分块数、被过滤数、是否空内容、质量分）。
     *
     * <p>质量分定义为"有效 chunk 占比"——原始分块中保留下来的、达到 {@link #MIN_CHUNK_CHARS}
     * 的 chunk 比例，范围 0.0~1.0；空文档质量分为 0.0。
     *
     * @return 分块结果与质量指标，空文档返回空 chunk 列表 + emptyContent=true
     */
    public ChunkingResult splitWithQuality(String content) {
        if (content == null || content.isBlank()) {
            return new ChunkingResult(List.of(), 0, 0, 0, true, 0.0d);
        }
        int documentLength = content.length();
        List<TextSegment> rawChunks = split(content);
        List<TextSegment> effectiveChunks = new ArrayList<>(rawChunks.size());
        int filteredChunkCount = 0;
        for (TextSegment chunk : rawChunks) {
            String text = chunk.text() == null ? "" : chunk.text().trim();
            if (text.length() < MIN_CHUNK_CHARS) {
                filteredChunkCount++;
                continue;
            }
            effectiveChunks.add(chunk);
        }
        int rawChunkCount = rawChunks.size();
        double qualityScore = rawChunkCount == 0
            ? 0.0d
            : (double) effectiveChunks.size() / rawChunkCount;
        log.info("分块质量评估: documentLength={}, rawChunkCount={}, filteredChunkCount={}, effective={}, qualityScore={}",
            documentLength, rawChunkCount, filteredChunkCount, effectiveChunks.size(), qualityScore);
        return new ChunkingResult(effectiveChunks, documentLength, rawChunkCount, filteredChunkCount, false, qualityScore);
    }

    /**
     * 分块质量评估结果。
     *
     * @param chunks            过滤噪声后的有效 chunk（向量化实际入库的内容）
     * @param documentLength    原始文档字符长度
     * @param rawChunkCount     过滤前的原始分块数
     * @param filteredChunkCount被过滤掉的噪声 chunk 数（空白或短于 {@link #MIN_CHUNK_CHARS}）
     * @param emptyContent      是否为空文档（null 或纯空白），空文档不入库
     * @param qualityScore      质量分，有效 chunk 占比，范围 0.0~1.0，空文档为 0.0
     */
    public record ChunkingResult(
        List<TextSegment> chunks,
        int documentLength,
        int rawChunkCount,
        int filteredChunkCount,
        boolean emptyContent,
        double qualityScore
    ) {
    }
}
