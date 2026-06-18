package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.service.chunk.AutoChunkStrategy;
import interview.guide.modules.knowledgebase.service.chunk.ChunkStrategy;
import interview.guide.modules.knowledgebase.service.chunk.HybridHeadingChunkStrategy;
import interview.guide.modules.knowledgebase.service.chunk.RecursiveCharacterChunkStrategy;
import interview.guide.modules.knowledgebase.service.chunk.SemanticChunkStrategy;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库分片服务。
 * <p>
 * 负责把原始文档切成检索友好的 chunk，并提供文档级质量评估。具体的切分规则交给
 * {@link ChunkStrategy} 实现，按配置 {@code app.ai.rag.chunk-strategy} 选定：
 * <ul>
 *   <li>{@code hybrid}（默认）：保留 Markdown/普通标题结构，章节内再做 token 分片</li>
 *   <li>{@code recursive}：递归字符分片，按分隔符层级切，尽量不切断句子</li>
 *   <li>{@code semantic}：按完整句子累积成块</li>
 *   <li>{@code auto}：根据文档结构特征自动挑上述之一</li>
 * </ul>
 * 相邻 chunk 的重叠由 {@code app.ai.rag.chunk-overlap-chars} 统一控制。
 */
@Slf4j
@Service
public class KnowledgeBaseChunkingService {

    /**
     * chunk 最短有效长度（trim 后字符数）。低于此值的 chunk 视为噪声：
     * 可能是标题切分残留、纯符号行或分块器边界碎片，入库只会污染检索结果并浪费 embedding 调用。
     */
    private static final int MIN_CHUNK_CHARS = 5;

    private final TextSplitter tokenTextSplitter;

    /**
     * 相邻 chunk 的重叠字符数：从上一个 chunk 末尾截取等长文本拼到下一个 chunk 开头，
     * 降低分块器在边界处硬切断、导致召回时丢失上下文的风险。
     * 只在同一分片段内部相邻 chunk 之间生效，不跨章节拼凑无关内容。0 表示关闭重叠。
     */
    @Value("${app.ai.rag.chunk-overlap-chars:80}")
    private int overlapChars = 80;

    /** 分片策略名：hybrid(默认)/recursive/semantic/auto。 */
    @Value("${app.ai.rag.chunk-strategy:hybrid}")
    private String chunkStrategyName = "hybrid";

    /** recursive/semantic/auto 使用的目标字符长度；hybrid 仍按 TokenTextSplitter 的 token 切。 */
    @Value("${app.ai.rag.chunk-size-chars:800}")
    private int chunkSizeChars = 800;

    private ChunkStrategy chunkStrategy;

    public KnowledgeBaseChunkingService() {
        this(TokenTextSplitter.builder().build());
    }

    KnowledgeBaseChunkingService(TextSplitter tokenTextSplitter) {
        this.tokenTextSplitter = tokenTextSplitter;
        // 单元测试不走 Spring 注入，用字段默认值（hybrid + overlap 80）初始化一次，
        // 保证 new KnowledgeBaseChunkingService() 的行为与既有测试一致。
        this.chunkStrategy = resolveStrategy(chunkStrategyName);
    }

    @PostConstruct
    void initStrategy() {
        // Spring 注入完配置后，按真实值重新选定策略。new 出来的测试实例不走这里。
        this.chunkStrategy = resolveStrategy(chunkStrategyName);
    }

    private ChunkStrategy resolveStrategy(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase();
        switch (normalized) {
            case "recursive":
                return new RecursiveCharacterChunkStrategy(chunkSizeChars);
            case "semantic":
                return new SemanticChunkStrategy(chunkSizeChars);
            case "auto":
                return new AutoChunkStrategy(
                        new RecursiveCharacterChunkStrategy(chunkSizeChars),
                        new SemanticChunkStrategy(chunkSizeChars),
                        new HybridHeadingChunkStrategy(tokenTextSplitter),
                        chunkSizeChars);
            case "hybrid":
            default:
                return new HybridHeadingChunkStrategy(tokenTextSplitter);
        }
    }

    public List<Document> split(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<Document> chunks = chunkStrategy.split(content, overlapChars);
        log.info("分片完成: strategy={}, chunks={}", chunkStrategy.name(), chunks.size());
        return chunks;
    }

    /**
     * 带质量评估的分块：在 {@link #split(String)} 基础上过滤空白/过短的噪声 chunk，
     * 并产出文档级质量指标（文档长度、原始分块数、被过滤数、是否空内容、质量分）。
     *
     * <p>质量分定义为“有效 chunk 占比”——原始分块中保留下来的、达到 {@link #MIN_CHUNK_CHARS}
     * 的 chunk 比例，范围 0.0~1.0；空文档质量分为 0.0。该指标用于向量化前判断文档是否值得入库，
     * 以及检索质量下滑时的事后归因（例如某知识库召回变差，可回溯其分块质量分是否偏低）。
     *
     * @return 分块结果与质量指标，空文档返回空 chunk 列表 + emptyContent=true
     */
    public ChunkingResult splitWithQuality(String content) {
        if (content == null || content.isBlank()) {
            return new ChunkingResult(List.of(), 0, 0, 0, true, 0.0d);
        }
        int documentLength = content.length();
        List<Document> rawChunks = split(content);
        List<Document> effectiveChunks = new ArrayList<>(rawChunks.size());
        int filteredChunkCount = 0;
        for (Document chunk : rawChunks) {
            String text = chunk.getText() == null ? "" : chunk.getText().trim();
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
            List<Document> chunks,
            int documentLength,
            int rawChunkCount,
            int filteredChunkCount,
            boolean emptyContent,
            double qualityScore
    ) {
    }
}
