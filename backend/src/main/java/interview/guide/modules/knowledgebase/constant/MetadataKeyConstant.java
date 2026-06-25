package interview.guide.modules.knowledgebase.constant;

/**
 * 知识库向量元数据键常量（对齐 know-engine MetadataKeyConstant）。
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

    /** 访问权限（用户隔离用，存 user_id） */
    public static final String ACCESSIBLE_BY = "accessibleBy";

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
