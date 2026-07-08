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
    private Sql sql = new Sql();
    private Routing routing = new Routing();
    private Graph graph = new Graph();
    private Generation generation = new Generation();
    private TitleSummary titleSummary = new TitleSummary();
    private Decompose decompose = new Decompose();
    private Crag crag = new Crag();
    private Adaptive adaptive = new Adaptive();
    private int chunkOverlapChars = 80;
    private String chunkStrategy = "hybrid";
    private int chunkSizeChars = 800;
    private String systemPromptPath = "classpath:prompts/knowledgebase-query-system.st";
    private String userPromptPath = "classpath:prompts/knowledgebase-query-user.st";
    private String rewritePromptPath = "classpath:prompts/knowledgebase-query-rewrite.st";
    private String hydePromptPath = "classpath:prompts/knowledgebase-query-hyde.st";
    private String decomposePromptPath = "classpath:prompts/rag/decompose.st";
    private String cragPromptPath = "classpath:prompts/rag/crag-grade.st";

    @Data
    public static class Rewrite {
        private boolean enabled = true;
        /** 查询改写专用模型；空则复用默认 Provider 的 Chat 模型 */
        private String model = "";
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
     * 混合检索配置：ES 向量通道 + 全文通道。
     */
    @Data
    public static class Hybrid {
        /** 是否启用混合检索；关闭时退回纯向量检索 */
        private boolean enabled = true;
        /**
         * 双通道并行：注册独立的 vector + full_text 两路 ES retriever（对齐业界实践），
         * 由 Aggregator RRF 融合。开启后忽略 {@link #mode} 的单 retriever 模式切换。
         */
        private boolean dualChannel = true;
        /** 检索模式：hybrid / vector / full_text（dualChannel=false 时生效） */
        private String mode = "hybrid";
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
     * 重排配置：默认使用 DashScope gte-rerank 云端；显式 provider=local 时才启用本地 ONNX。
     */
    @Data
    public static class Rerank {
        /** 是否启用重排；关闭或失败时退回 RRF 融合排序 */
        private boolean enabled = true;
        /** 重排实现：local（本地 ONNX BGE，优先）或 cloud（DashScope gte-rerank）；local 不可用时自动降级 cloud */
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
        private boolean enabled = true;
        /**
         * 扩展策略：append（命中+兄弟+父块拼接）或 replace（有父块时用父块替换子块，对齐业界实践）。
         */
        private String strategy = "replace";
        /** 单个命中 chunk 扩展后的最大字符数 */
        private int maxChars = 1200;
        /** 最多聚合的兄弟 chunk 数 */
        private int maxSiblings = 5;
        /** parent/brother 文本 Redis 缓存 TTL（秒），0 表示不缓存 */
        private int cacheTtlSeconds = 30;
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
        /** 意图识别专用模型；空则复用默认 ChatModel */
        private String model = "";
        /** LLM 语义识别在三路融合中的权重 */
        private double llmWeight = 0.6;
        /** 面试意图样例相似度在三路融合中的权重 */
        private double vectorWeight = 0.25;
        /** 关键词规则兜底在三路融合中的权重 */
        private double ruleWeight = 0.15;
        /** 最低综合置信度；低于该值时按 OFF_TOPIC 处理 */
        private double minConfidence = 0.3;
        /** 本地识别缓存上限；达到上限后清空，避免无界增长 */
        private int cacheMaxSize = 1000;
        /** 意图识别缓存 key 纳入的最近历史消息数 */
        private int maxHistoryMessages = 6;
        /** 单条历史消息进入意图识别 prompt/cache key 的最大字符数 */
        private int historyMessageMaxChars = 160;
    }

    /**
     * 查询路由 / Text2SQL / Text2Cypher 专用模型（对齐业界实践）。
     */
    @Data
    public static class Routing {
        private String model = "";
    }

    /**
     * Neo4j Text2Cypher 图检索配置。
     */
    @Data
    public static class Graph {
        private boolean enabled = true;
        private String cypherPromptPath = "classpath:prompts/text-to-cypher-prompt.txt";
        /** 向量化完成后自动从分段抽取概念节点写入 Neo4j */
        private boolean autoSyncOnVectorize = true;
        /** 启动时从 skills 目录各子目录 SKILL.md 预置 Skill 图谱 */
        private boolean skillBootstrapEnabled = true;
        /** 实体级图谱（P2 加深）：LLM 实体抽取同步 + 实体锚点检索 */
        private Entity entity = new Entity();

        /**
         * 实体级图谱配置：向量化完成后 LLM 从 chunk 抽取技术实体与关系，写
         * {@code (:Entity)-[:RELATES{type}]->(:Entity)} 与 {@code (:Entity)-[:MENTIONED_IN]->(:Chunk)}；
         * 检索期以问题命中的实体为锚点做 2 跳遍历回捞关联 chunk（带关系路径说明）。
         */
        @Data
        public static class Entity {
            /** 是否在向量化完成后追加 LLM 实体抽取同步（失败不阻断主链路） */
            private boolean extractionEnabled = true;
            /** 实体抽取专用模型（建议最便宜模型）；空则复用路由模型 */
            private String model = "";
            /** 每次 LLM 调用携带的 chunk 数 */
            private int batchSize = 8;
            /** 实体抽取批次并发上限（虚拟线程） */
            private int maxConcurrency = 4;
            /** 实体抽取 prompt 模板路径 */
            private String promptPath = "classpath:prompts/rag/graph-entity-extract.st";
            /** 检索期问题实体锚点上限 */
            private int maxAnchors = 5;
            /** 检索期图谱遍历路径条数上限 */
            private int maxPaths = 20;
            /** 检索期回捞关联 chunk 上限 */
            private int maxChunks = 6;
        }
    }

    /**
     * RAG 流式生成模型配置（对齐业界实践 ragChatModel）。
     */
    @Data
    public static class Generation {
        /** 流式回答专用模型；空则复用默认 StreamingChatModel */
        private String streamingModel = "qwen3.6-plus";
        private double temperature = 0.2;
    }

    /**
     * Text2SQL 结构化检索：查询当前用户的简历、面试记录、评分和日程。
     */
    @Data
    public static class Sql {
        /** 默认关闭：面试备考知识库无结构化数据问答场景，保留实现按需开启 */
        private boolean enabled = false;
        private boolean routerEnabled = false;
        private int queryTimeoutSeconds = 8;
        private int maxRows = 100;
        /** Text2SQL Prompt 模板（对齐业界实践 text-to-sql-prompt.txt） */
        private String promptPath = "classpath:prompts/text-to-sql-prompt.txt";
    }

    /**
     * Query Decomposition（P2 Agentic RAG）：LLM 判定复杂问题（多跳/对比/综合）后拆解成
     * 2-4 个可独立检索的子查询，子查询并行走现有检索链，结果由 RRF 聚合器融合去重。
     * 简单问题经规则预筛直接跳过，不产生额外 LLM 调用。
     */
    @Data
    public static class Decompose {
        /** 是否启用复杂问题分解 */
        private boolean enabled = true;
        /** 单次分解产出的子查询上限 */
        private int maxSubQueries = 4;
        /** 分解专用模型；空则复用路由模型 */
        private String model = "";
        /** 查询分解 Prompt 模板 */
        private String promptPath = "classpath:prompts/rag/decompose.st";
    }

    /**
     * CRAG 纠正式检索（P2 Agentic RAG）：rerank 后让小模型对 top-N 打分
     * correct / ambiguous / incorrect——correct 直接生成；ambiguous 用纠正查询重检索一次
     * （硬上限 1，防循环）；incorrect 走通用对话兜底并明确告知「知识库无据」（防幻觉）。
     */
    @Data
    public static class Crag {
        /** 是否启用纠正式检索（每次查询增加一次小模型调用，默认关闭按需开启） */
        private boolean enabled = false;
        /** 参与打分的 top-N 片段数 */
        private int gradeTopN = 3;
        /** 打分片段单条截断字符数 */
        private int snippetMaxChars = 400;
        /** 打分专用模型（建议小模型）；空则复用路由模型 */
        private String model = "";
        /** CRAG 打分 Prompt 模板 */
        private String promptPath = "classpath:prompts/rag/crag-grade.st";
    }

    /**
     * 自适应检索路由（P2 Agentic RAG）：意图识别输出 needRetrieval 判定，寒暄/闲聊/
     * 纯常识定义且模型自信时跳过检索直接生成（trace 记录「跳过检索」），节省检索与 token 成本。
     */
    @Data
    public static class Adaptive {
        /** 是否启用自适应跳过检索（依赖 intent-recognition.enabled=true） */
        private boolean enabled = true;
    }

    /**
     * 异步 LLM 标题生成配置（亮点6）：首问流式完成后用虚拟线程异步让 LLM 根据首问生成会话标题，
     * 替代知识库名规则拼接。失败保留原规则标题。默认开启，关闭则保留原规则标题。
     */
    @Data
    public static class TitleSummary {
        /** 是否启用 LLM 异步标题生成；关闭则保留原规则标题（知识库名 / "N 个知识库对话"） */
        private boolean enabled = true;
        /** 标题摘要专用模型（对齐业界实践：qwen3.5-flash） */
        private String model = "qwen3.5-flash";
    }
}
