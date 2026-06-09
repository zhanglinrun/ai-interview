package interview.guide.modules.knowledgebase.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai.rag")
public class KnowledgeBaseQueryProperties {

    private Rewrite rewrite = new Rewrite();
    private Search search = new Search();
    private History history = new History();
    private Hybrid hybrid = new Hybrid();
    private Rerank rerank = new Rerank();
    private String systemPromptPath = "classpath:prompts/knowledgebase-query-system.st";
    private String userPromptPath = "classpath:prompts/knowledgebase-query-user.st";
    private String rewritePromptPath = "classpath:prompts/knowledgebase-query-rewrite.st";

    @Data
    public static class Rewrite {
        private boolean enabled = true;
    }

    @Data
    public static class Search {
        private int shortQueryLength = 4;
        private int topkShort = 20;
        private int topkMedium = 12;
        private int topkLong = 8;
        private double minScoreShort = 0.25;
        private double minScoreDefault = 0.28;
    }

    @Data
    public static class History {
        private boolean enabled = true;
        private int maxMessages = 10;
    }

    /**
     * 混合检索配置：向量通道 + 关键词通道（pg_trgm），RRF 融合。
     */
    @Data
    public static class Hybrid {
        /** 是否启用混合检索；关闭时退回纯向量检索 */
        private boolean enabled = true;
        /** 关键词通道单独召回的候选数 */
        private int keywordTopK = 20;
        /** 关键词通道 word_similarity 最低阈值，过滤弱相关 */
        private double keywordMinSimilarity = 0.1;
        /** RRF 融合常数 k，越大越平滑各通道排名差异 */
        private int rrfK = 60;
        /** 融合后进入重排/上下文的候选上限 */
        private int fusionTopK = 12;
    }

    /**
     * 重排配置：调用 DashScope gte-rerank 对融合候选重排。
     */
    @Data
    public static class Rerank {
        /** 是否启用重排；关闭或失败时退回 RRF 融合排序 */
        private boolean enabled = true;
        /** 重排模型名 */
        private String model = "gte-rerank-v2";
        /** 重排后保留的文档数 */
        private int topN = 6;
        /** 单次重排超时（毫秒） */
        private long timeoutMs = 3000;
    }
}
