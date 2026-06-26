package com.linrun.interview.modules.knowledgebase.service;

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
    private IntentRecognition intentRecognition = new IntentRecognition();
    private TitleSummary titleSummary = new TitleSummary();
    private int chunkOverlapChars = 80;
    private String chunkStrategy = "hybrid";
    private int chunkSizeChars = 800;
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
     * 重排配置：默认本地 ONNX BGE-RERANKER 进程内精排，加载失败/无模型文件时自动降级 DashScope gte-rerank 云端。
     */
    @Data
    public static class Rerank {
        /** 是否启用重排；关闭或失败时退回 RRF 融合排序 */
        private boolean enabled = true;
        /** 重排实现：local（本地 ONNX BGE）或 cloud（DashScope gte-rerank）；默认 local */
        private String provider = "local";
        /** 重排服务 baseUrl（DashScope 默认 https://dashscope.aliyuncs.com） */
        private String baseUrl = "https://dashscope.aliyuncs.com";
        /** 重排模型名 */
        private String model = "gte-rerank-v2";
        /** 重排后保留的文档数 */
        private int topN = 6;
        /** 单次重排超时（毫秒） */
        private long timeoutMs = 3000;
        /** 本地 ONNX reranker 配置（仅 provider=local 时生效） */
        private LocalOnnx local = new LocalOnnx();

        @Data
        public static class LocalOnnx {
            /** ONNX 模型文件 classpath 路径（来自 onnx-community/bge-reranker-v2-m3-ONNX 的 model_quantized.onnx） */
            private String modelPath = "classpath:model/bge-reranker-model/model_quantized.onnx";
            /** tokenizer 文件 classpath 路径（来自同仓库的 tokenizer.json） */
            private String tokenizerPath = "classpath:model/bge-reranker-model/tokenizer.json";
            /** 模型最大序列长度（BGE-RERANKER-v2-m3 默认 8192） */
            private int maxSequenceLength = 8192;
        }
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

    /**
     * 意图识别兜底配置（亮点4）：流式问答前置一次意图识别，判定问题是否与面试 / 技术知识 /
     * 简历 / 求职等场景相关。不相关走 {@link com.linrun.interview.modules.knowledgebase.service.CommonChatService}
     * 通用对话兜底（不检索知识库），避免越界问题强行检索导致幻觉。默认开启，关闭则全部走 RAG。
     */
    @Data
    public static class IntentRecognition {
        /** 是否启用意图识别兜底；关闭则全部走 RAG（行为同批次A） */
        private boolean enabled = true;
        /** 是否在意图识别前推"正在理解您的问题..."进度（关闭可省 ~0.4s 前端空进度） */
        private boolean progressEnabled = true;
    }

    /**
     * 异步 LLM 标题生成配置（亮点6）：首问流式完成后用虚拟线程异步让 LLM 根据首问生成会话标题，
     * 替代知识库名规则拼接。失败保留原规则标题。默认开启，关闭则保留原规则标题。
     */
    @Data
    public static class TitleSummary {
        /** 是否启用 LLM 异步标题生成；关闭则保留原规则标题（知识库名 / "N 个知识库对话"） */
        private boolean enabled = true;
    }
}
