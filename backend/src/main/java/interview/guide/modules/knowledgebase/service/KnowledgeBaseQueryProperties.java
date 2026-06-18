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
    private Citation citation = new Citation();
    private Hyde hyde = new Hyde();
    private Fusion fusion = new Fusion();
    private ParentExpand parentExpand = new ParentExpand();
    private String systemPromptPath = "classpath:prompts/knowledgebase-query-system.st";
    private String userPromptPath = "classpath:prompts/knowledgebase-query-user.st";
    private String rewritePromptPath = "classpath:prompts/knowledgebase-query-rewrite.st";
    private String hydePromptPath = "classpath:prompts/knowledgebase-query-hyde.st";

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

    /**
     * 引用溯源配置：给上下文片段编号，要求模型用 [n] 标注来源，生成后校验引用真实性并算置信度。
     */
    @Data
    public static class Citation {
        /** 是否启用引用编号与置信度；关闭时退回无编号的原始上下文 */
        private boolean enabled = true;
        /** 综合置信度里引用覆盖率的权重（其余为平均相似度权重） */
        private double coverageWeight = 0.5;
        /** 每个无效（编造）引用编号对置信度的扣分 */
        private double invalidPenalty = 0.1;
    }

    /**
     * HyDE（Hypothetical Document Embeddings）配置：先让 LLM 就问题生成一段假设性答案，
     * 用该答案的向量做检索，提升"问题表述"与"答案表述"之间的语义鸿沟。
     * 默认关闭，评测/线上按需开启；生成失败自动降级跳过该路。
     */
    @Data
    public static class Hyde {
        /** 是否启用 HyDE 假设文档召回 */
        private boolean enabled = false;
        /** 假设文档最大字符数，超出截断 */
        private int maxChars = 300;
        /** HyDE 生成超时（毫秒） */
        private long timeoutMs = 4000;
    }

    /**
     * 多路召回融合配置：把原问题 / rewrite / HyDE 多个候选 query 各做一次混合检索，
     * 再用跨路 RRF 融合排名。关闭时退回串行短路（首个命中 query 即返回）的历史行为。
     */
    @Data
    public static class Fusion {
        /** 是否启用多路并行召回 + 跨路 RRF 融合 */
        private boolean enabled = false;
        /** 多路融合时，每路单独召回的候选数 */
        private int perQueryTopK = 12;
        /** 跨路 RRF 融合常数 k */
        private int rrfK = 60;
        /** 融合后交给重排/上下文的候选上限 */
        private int finalTopK = 12;
    }

    /**
     * 父子 chunk / small-to-big 配置：检索用小 chunk 精准命中，
     * 喂给 LLM 前把同段兄弟 chunk 聚合成更大上下文（基于 parent_section 元数据）。
     * 默认关闭；需向量化的 chunk 带 parent_section（hybrid 分段策略产出）。
     */
    @Data
    public static class ParentExpand {
        /** 是否启用 small-to-big 上下文扩展 */
        private boolean enabled = false;
        /** 单个命中 chunk 扩展后的最大字符数 */
        private int maxChars = 1200;
        /** 最多聚合的兄弟 chunk 数 */
        private int maxSiblings = 5;
    }
}
