package com.linrun.interview.rag.config;
import com.linrun.interview.chat.service.CommonChatService;


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
    private Context context = new Context();
    private Hyde hyde = new Hyde();
    private Fusion fusion = new Fusion();
    private ParentExpand parentExpand = new ParentExpand();
    private IntentRecognition intentRecognition = new IntentRecognition();
    private Generation generation = new Generation();
    private TitleSummary titleSummary = new TitleSummary();
    private Decompose decompose = new Decompose();
    private Crag crag = new Crag();
    private Adaptive adaptive = new Adaptive();
    private MultiSource multiSource = new MultiSource();
    /**
     * 意图 / 改写 / HyDE / 路由 / 分解共用的快模型。各子项 model 非空时覆盖。
     * 生成仍走 {@code generation.streaming-model} 或用户 BYOK。
     */
    private String decisionModel = "qwen3.5-flash";
    private int chunkOverlapChars = 80;
    private String chunkStrategy = "hybrid";
    private int chunkSizeChars = 800;
    private String systemPromptPath = "classpath:prompts/rag/knowledgebase-query-system.txt";
    private String userPromptPath = "classpath:prompts/rag/knowledgebase-query-user.txt";
    private String rewritePromptPath = "classpath:prompts/rag/knowledgebase-query-rewrite.txt";
    private String hydePromptPath = "classpath:prompts/rag/knowledgebase-query-hyde.txt";
    private String decomposePromptPath = "classpath:prompts/rag/decompose.txt";
    private String cragPromptPath = "classpath:prompts/rag/crag-grade.txt";

    public String resolveDecisionModel(String override) {
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return decisionModel == null || decisionModel.isBlank() ? "qwen3.5-flash" : decisionModel.trim();
    }

    @Data
    public static class Rewrite {
        private boolean enabled = true;
        /** 查询改写专用模型；空则使用 {@link #decisionModel} */
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
     * 重排配置：仅使用本地 ONNX BGE-RERANKER。
     */
    @Data
    public static class Rerank {
        /** 是否启用重排；关闭或本地模型不可用时退回 RRF 融合排序 */
        private boolean enabled = true;
        /**
         * BGE rerank 最低分，按 0～1 理解；原始 logit 会先做 sigmoid。
         * 默认 0.88 约等于 logit 2.0，用来挡「穿透/击穿」这类近义词干扰。
         */
        private double minScore = 0.88;
        /** 重排后保留的文档数 */
        private int topN = 6;
        /** 本地模型缺失时是否 fail-fast 阻断启动（生产建议 true） */
        private boolean failFastOnMissingModel = false;
        /** 本地 ONNX BGE-RERANKER 配置 */
        private LocalOnnx local = new LocalOnnx();

        @Data
        public static class LocalOnnx {
            /** ONNX 模型文件 classpath 路径（来自 onnx-community/bge-reranker-v2-m3-ONNX 的 model_quantized.onnx） */
            private String modelPath = "classpath:model/bge-reranker-model/model_quantized.onnx";
            /** tokenizer 文件 classpath 路径（来自同仓库的 tokenizer.json） */
            private String tokenizerPath = "classpath:model/bge-reranker-model/tokenizer.json";
            /** 模型最大序列长度（BGE-RERANKER-v2-m3 默认 8192） */
            private int maxSequenceLength = 4096;
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

    /** 生成前的检索上下文总预算。 */
    @Data
    public static class Context {
        /** 全部最终片段正文的最大字符数；小于 1 表示不限制。 */
        private int maxTotalChars = 18000;
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
         * 扩展策略：keep/append（命中+兄弟+父块拼接）或 replace（有父块时用父块替换子块）。
         */
        private String strategy = "keep";
        /** 单个命中 chunk 扩展后的最大字符数 */
        private int maxChars = 4000;
        /** 最多聚合的兄弟 chunk 数 */
        private int maxSiblings = 5;
        /** parent/brother 文本 Redis 缓存 TTL（秒），0 表示不缓存 */
        private int cacheTtlSeconds = 30;
    }

    /**
     * 意图识别兜底配置（亮点4）：流式问答前置一次意图识别，判定问题是否与面试 / 技术知识 /
     * 简历 / 求职等场景相关。不相关走 {@link com.linrun.interview.chat.service.CommonChatService}
     * 通用对话兜底（不检索知识库），避免越界问题强行检索导致幻觉。默认开启，关闭则全部走 RAG。
     */
    @Data
    public static class IntentRecognition {
        /** 是否启用意图识别兜底；关闭则全部走 RAG（行为同批次A） */
        private boolean enabled = true;
        /** 是否在意图识别前推"正在理解您的问题..."进度（关闭可省 ~0.4s 前端空进度） */
        private boolean progressEnabled = true;
        /** 意图识别专用模型；空则使用 {@link KnowledgeBaseQueryProperties#decisionModel} */
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
     * RAG 流式生成模型配置（对齐业界实践 ragChatModel）。
     */
    @Data
    public static class Generation {
        /** 流式回答专用模型；空则复用默认 StreamingChatModel */
        private String streamingModel = "qwen3.6-plus";
        private double temperature = 0.2;
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
        private String promptPath = "classpath:prompts/rag/decompose.txt";
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
        private String promptPath = "classpath:prompts/rag/crag-grade.txt";
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
     * 多数据源 RAG 配置：按问题类型路由到 ES、MySQL 或 Neo4j。
     *
     * <p>ES 是平台默认数据源；关系库和图数据库均为可选能力，未配置或不可用时
     * 自动回退到 ES，不影响现有知识库问答链路。</p>
     */
    @Data
    public static class MultiSource {
        /** 是否启用三源路由。关闭时保持原有 ES-only 行为。 */
        private boolean enabled = true;
        /** 是否让 LLM 参与路由；关闭时使用规则路由，降低延迟和成本。 */
        private boolean llmRouteEnabled = true;
        /** 路由专用模型；空则使用 {@link KnowledgeBaseQueryProperties#decisionModel} */
        private String routeModel = "";
        /** SQL 数据源配置。 */
        private Sql sql = new Sql();
        /** Neo4j 数据源配置。 */
        private Neo4j neo4j = new Neo4j();

        @Data
        public static class Sql {
            private boolean enabled = true;
            /** 允许 Text2SQL 访问的表；空值时从 information_schema 读取业务表白名单。 */
            private java.util.List<String> allowedTables = new java.util.ArrayList<>();
            /** Schema 元数据缓存秒数。 */
            private long schemaCacheSeconds = 300;
            /** 单次查询最多返回行数。 */
            private int maxRows = 100;
            /** SQL Prompt 模板。 */
            private String promptPath = "classpath:prompts/rag/text-to-sql.txt";
        }

        @Data
        public static class Neo4j {
            private boolean enabled = true;
            private String uri = "bolt://localhost:7687";
            private String username = "neo4j";
            private String password = "";
            private String database = "neo4j";
            /** 领域实体图 Schema，供 Text2Cypher 生成器使用；生产环境可通过环境变量覆盖。 */
            private String schema = "节点：KnowledgeEntity(entityId,name,normalizedName,entityType,aliases,description,scope,ownerUserId,projectionSource)；关系：(source:KnowledgeEntity)-[:RELATES_TO {relationId,relationType,description,confidence}]->(target:KnowledgeEntity)。关系类型放在 relationType 属性中，例如 USES、BUILDS_ON、DEPENDS_ON、INTEGRATES_WITH、PART_OF、RETRIEVES_FROM、RERANKS、PERSISTS、ROUTES_TO；图谱用于 Agent、LangChain、LangGraph、RAG、数据库和基础设施等业务实体关系，不存放文档分段父子关系。查询必须只读。";
            /** 应用启动/补偿时是否幂等写入平台领域图谱种子。 */
            private boolean seedEnabled = true;
            /** 领域图谱种子资源，使用 JSON 而不是让应用执行任意 Cypher。 */
            private String seedPath = "classpath:neo4j/ai-interview-domain.json";
            /** 平台公开实体的 ownerUserId；Text2Cypher 会同时允许当前用户和该值。 */
            private long platformOwnerId = 0L;
            /** 只有图谱明确包含文档证据节点时才开启；当前领域图默认关闭。 */
            private boolean knowledgeBaseScopeRequired = false;
            /** 图谱包含用户私有节点时默认强制 Cypher 使用图谱 owner 范围条件。 */
            private boolean userScopeRequired = true;
            private String userScopeProperty = "ownerUserId";
            /** 单次 Text2Cypher 查询最多返回的行数。 */
            private int maxRows = 100;
            private int maxRetries = 1;
            private String promptPath = "classpath:prompts/rag/text-to-cypher.txt";
        }
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
