package interview.guide.modules.knowledgebase.service.chunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("分片策略测试")
class ChunkStrategyTest {

    @Test
    @DisplayName("recursive：长文本按分隔符切成多块，每块不超过 chunkSize")
    void recursiveShouldSplitWithinChunkSize() {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            content.append("段落一。");
        }

        ChunkStrategy strategy = new RecursiveCharacterChunkStrategy(100);
        List<Document> chunks = strategy.split(content.toString(), 0);

        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        for (Document chunk : chunks) {
            assertThat(chunk.getText().trim()).isNotEmpty();
            assertThat(chunk.getText().length()).isLessThanOrEqualTo(100);
        }
    }

    @Test
    @DisplayName("recursive：空内容返回空列表")
    void recursiveShouldReturnEmptyForBlank() {
        assertThat(new RecursiveCharacterChunkStrategy(40).split("  \n  ", 0)).isEmpty();
        assertThat(new RecursiveCharacterChunkStrategy(40).split(null, 0)).isEmpty();
    }

    @Test
    @DisplayName("semantic：按完整句子累积成块，每块不超过 chunkSize")
    void semanticShouldAccumulateSentences() {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            content.append("这是一句用于验证语义分片累积逻辑的中文句子。");
        }

        ChunkStrategy strategy = new SemanticChunkStrategy(100);
        List<Document> chunks = strategy.split(content.toString(), 0);

        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        for (Document chunk : chunks) {
            assertThat(chunk.getText().trim()).isNotEmpty();
            assertThat(chunk.getText().length()).isLessThanOrEqualTo(100);
        }
    }

    @Test
    @DisplayName("semantic：短文本不超过 chunkSize 时退化为单块")
    void semanticShouldProduceSingleChunkForShortText() {
        String content = "Redis 是基于内存的存储。";
        List<Document> chunks = new SemanticChunkStrategy(200).split(content, 0);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getText()).contains("Redis");
    }

    @Test
    @DisplayName("hybrid：无标题时退回 token 分片且不带章节元数据")
    void hybridShouldFallbackWithoutSectionMetadata() {
        String content = "Redis 是基于内存的数据结构存储，常用于缓存、排行榜和分布式锁。";
        List<Document> chunks = new HybridHeadingChunkStrategy().split(content, 0);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.getFirst().getMetadata()).doesNotContainKey("section_title");
    }

    @Test
    @DisplayName("auto：有标题选 hybrid、长无标题选 recursive、短无标题选 semantic")
    void autoShouldChooseStrategyByStructure() {
        ChunkStrategy recursive = new RecursiveCharacterChunkStrategy(800);
        ChunkStrategy semantic = new SemanticChunkStrategy(800);
        ChunkStrategy hybrid = new HybridHeadingChunkStrategy();
        AutoChunkStrategy auto = new AutoChunkStrategy(recursive, semantic, hybrid, 800);

        String withHeadings = """
            # Redis

            Redis 是基于内存的存储。

            ## 持久化

            Redis 支持 RDB 和 AOF。
            """;
        assertThat(auto.chosenStrategy(withHeadings)).isEqualTo("hybrid");

        StringBuilder longNoHeading = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longNoHeading.append("这是一段没有任何标题结构的较长正文内容，用来触发递归分片。");
        }
        assertThat(auto.chosenStrategy(longNoHeading.toString())).isEqualTo("recursive");

        String shortNoHeading = "Redis 是基于内存的存储，常用于缓存。";
        assertThat(auto.chosenStrategy(shortNoHeading)).isEqualTo("semantic");
    }

    @Test
    @DisplayName("overlap：相邻 chunk 开头应拼上前一个的末尾 overlapChars 字符")
    void overlapShouldCarryTailFromPreviousChunk() {
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            content.append("段落一。");
        }

        List<Document> chunks = new RecursiveCharacterChunkStrategy(100).split(content.toString(), 8);

        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        for (int i = 1; i < chunks.size(); i++) {
            String prevText = chunks.get(i - 1).getText();
            String curText = chunks.get(i).getText();
            int overlap = Math.min(8, prevText.length());
            assertThat(curText).startsWith(prevText.substring(prevText.length() - overlap));
        }
    }
}
