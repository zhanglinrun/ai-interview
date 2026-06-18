package interview.guide.modules.knowledgebase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("知识库分片服务测试")
class KnowledgeBaseChunkingServiceTest {

    private final KnowledgeBaseChunkingService chunkingService = new KnowledgeBaseChunkingService();

    @Test
    @DisplayName("Markdown 标题文本应保留章节元数据")
    void shouldKeepMarkdownHeadingMetadata() {
        String content = """
            # Redis

            Redis 是基于内存的数据存储。

            ## 持久化

            Redis 支持 RDB、AOF 和混合持久化。
            """;

        List<Document> chunks = chunkingService.split(content);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks)
            .anySatisfy(chunk -> assertThat(chunk.getMetadata())
                .containsEntry("section_title", "Redis")
                .containsEntry("section_level", "1"));
        assertThat(chunks)
            .anySatisfy(chunk -> assertThat(chunk.getMetadata())
                .containsEntry("section_title", "持久化")
                .containsEntry("section_level", "2"));
    }

    @Test
    @DisplayName("普通标题文本应保留章节元数据")
    void shouldKeepPlainHeadingMetadata() {
        String content = """
            一、缓存问题

            缓存穿透可以用布隆过滤器或缓存空值解决。

            二、持久化

            Redis 支持 RDB 和 AOF。
            """;

        List<Document> chunks = chunkingService.split(content);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks)
            .extracting(chunk -> chunk.getMetadata().get("section_title"))
            .contains("一、缓存问题", "二、持久化");
    }

    @Test
    @DisplayName("无标题文本应退回普通 token 分片")
    void shouldFallbackForTextWithoutHeadings() {
        String content = "Redis 是基于内存的数据结构存储，常用于缓存、排行榜和分布式锁。";

        List<Document> chunks = chunkingService.split(content);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.getFirst().getMetadata()).doesNotContainKey("section_title");
    }

    @Test
    @DisplayName("空文本应返回空列表")
    void shouldReturnEmptyListForBlankContent() {
        assertThat(chunkingService.split("  \n  ")).isEmpty();
    }

    @Test
    @DisplayName("空文档质量评估应标记为空内容、质量分 0、不产出 chunk")
    void shouldFlagEmptyContentForBlankInput() {
        KnowledgeBaseChunkingService.ChunkingResult nullResult = chunkingService.splitWithQuality(null);
        assertThat(nullResult.emptyContent()).isTrue();
        assertThat(nullResult.chunks()).isEmpty();
        assertThat(nullResult.qualityScore()).isEqualTo(0.0d);
        assertThat(nullResult.documentLength()).isZero();

        KnowledgeBaseChunkingService.ChunkingResult blankResult = chunkingService.splitWithQuality("  \n  ");
        assertThat(blankResult.emptyContent()).isTrue();
        assertThat(blankResult.chunks()).isEmpty();
        assertThat(blankResult.qualityScore()).isEqualTo(0.0d);
    }

    @Test
    @DisplayName("正常文档质量评估应全保留有效 chunk、质量分 1.0")
    void shouldReportFullQualityForNormalContent() {
        String content = """
            # Redis

            Redis 是基于内存的数据存储，常用于缓存、排行榜和分布式锁。

            ## 持久化

            Redis 支持 RDB、AOF 和混合持久化，RDB 是周期性全量快照，AOF 记录每条写命令。
            """;

        KnowledgeBaseChunkingService.ChunkingResult result = chunkingService.splitWithQuality(content);

        assertThat(result.emptyContent()).isFalse();
        assertThat(result.documentLength()).isEqualTo(content.length());
        assertThat(result.chunks()).isNotEmpty();
        // 标题切分产出的 chunk 均长于最短阈值，无噪声被过滤
        assertThat(result.filteredChunkCount()).isZero();
        assertThat(result.rawChunkCount()).isEqualTo(result.chunks().size());
        assertThat(result.qualityScore()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("相邻 chunk 应有 overlap 重叠：后一个开头拼了前一个的末尾")
    void shouldOverlapAdjacentChunks() {
        // 构造足够长的无标题文本，确保 TokenTextSplitter 产出 ≥2 个 chunk
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            content.append("这是第").append(i).append("段用于验证分块重叠语义连续性的较长中文文本内容。");
        }

        List<Document> chunks = chunkingService.split(content.toString());

        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        // 默认 overlapChars=80：相邻 chunk 中，后一个的开头应等于前一个的末尾 80 字符
        for (int i = 1; i < chunks.size(); i++) {
            String prevText = chunks.get(i - 1).getText();
            String curText = chunks.get(i).getText();
            int overlap = Math.min(80, prevText.length());
            String expectedTail = prevText.substring(prevText.length() - overlap);
            assertThat(curText).startsWith(expectedTail);
        }
    }
}
