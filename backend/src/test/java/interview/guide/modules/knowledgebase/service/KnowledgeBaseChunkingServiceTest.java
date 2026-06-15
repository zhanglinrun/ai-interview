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
}
