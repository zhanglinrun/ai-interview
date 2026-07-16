package com.linrun.interview.modules.knowledgebase.constant;

/**
 * 知识库向量元数据键常量（对齐业界实践 MetadataKeyConstant）。
 *
 * <p>用于 Elasticsearch 向量库的 metadata 字段：父子切片关系、来源、权限等。
 */
public final class MetadataKeyConstant {

    private MetadataKeyConstant() {
    }

    /** 文件名称 */
    public static final String FILE_NAME = "fileName";

    /** 知识库 ID（对应 KnowledgeBaseEntity.id） */
    public static final String DOC_ID = "docId";

    /** 证据所有者；PLATFORM 固定为 0，其他域固定为真实 dataUserId。 */
    public static final String OWNER_USER_ID = "ownerUserId";

    /** 证据域：PLATFORM / JOB / CANDIDATE / GITHUB。 */
    public static final String DATA_DOMAIN = "dataDomain";

    /** 域内资源 ID，例如知识库 ID、JD ID 或 GitHub 仓库 ID。 */
    public static final String RESOURCE_ID = "resourceId";

    /** 冻结资源版本，例如知识库 versionId 或 Git commit SHA。 */
    public static final String RESOURCE_VERSION = "resourceVersion";

    /** 稳定片段证据 ID。 */
    public static final String EVIDENCE_ID = "evidenceId";

    /** 当前片段正文 SHA-256。 */
    public static final String CONTENT_HASH = "contentHash";

    /** 来源类型，例如 KNOWLEDGE_DOCUMENT / GITHUB_CODE。 */
    public static final String SOURCE_TYPE = "sourceType";

    /** 来源内定位，例如 chunk ID、文件路径与行号。 */
    public static final String SOURCE_LOCATOR = "sourceLocator";

    /** 本次 EvidenceScope 为所属域配置的召回权重（仅检索结果元数据）。 */
    public static final String DOMAIN_WEIGHT = "domainWeight";

    /** chunk 唯一 ID（雪花算法生成，用于父子/兄弟关系） */
    public static final String CHUNK_ID = "chunkId";

    /** 向量库返回的 embedding ID */
    public static final String EMBEDDING_ID = "EMBEDDING_ID";

    /** 父块 ID（父子切片：子 chunk 指向所属父 chunk） */
    public static final String PARENT_CHUNK_ID = "parentChunkId";

    /** 兄弟块 ID（兄弟切片：同一父 chunk 被二次切割出的同组子 chunk 共享） */
    public static final String BROTHER_CHUNK_ID = "brotherChunkId";

    /** 兄弟块序号（同组内的顺序，从 1 开始） */
    public static final String BROTHER_CHUNK_INDEX = "brotherChunkIndex";

    /** 兄弟块总数（同组子 chunk 总数） */
    public static final String BROTHER_CHUNK_TOTAL = "brotherChunkTotal";

    /** 标题级别（1-6，对应 Markdown # 的个数） */
    public static final String HEADER_LEVEL = "headerLevel";

    /** 访问权限（PRIVATE 存 user_id，PUBLIC 存 PUBLIC） */
    public static final String ACCESSIBLE_BY = "accessibleBy";

    /** 文档到期日（ISO-8601，过期分段不参与检索） */
    public static final String EXPIRE_DATE = "expireDate";

    /** 文件地址 */
    public static final String URL = "url";

    /** 文件版本 */
    public static final String VERSION = "version";

    /** 分类 */
    public static final String CATEGORY = "category";

    /** 摘要 */
    public static final String SUMMARY = "summary";

    /** 关键字 */
    public static final String KEYWORDS = "keywords";

    /** 跳过 embedding 标记：父 chunk 保留完整内容但不做 embedding，检索时靠子 chunk 命中再聚合 */
    public static final String SKIP_EMBEDDING = "skipEmbedding";
}
