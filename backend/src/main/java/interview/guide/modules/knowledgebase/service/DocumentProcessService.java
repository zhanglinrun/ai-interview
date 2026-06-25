package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseVersionEntity;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库文档处理编排接口（对齐 know-engine DocumentProcessService）。
 *
 * <p>编排上传→解析→切块→向量化的完整链路：
 * <ul>
 *   <li>{@link #upload}：校验→解析为 Markdown→存 RustFS→落库版本 v1→状态 UPLOADED/CONVERTED。</li>
 *   <li>{@link #split}：按 splitType 切块→落 segment 表→状态 CHUNKED→发布 {@code DocumentChunkedEvent}。</li>
 *   <li>{@link #embedAndStore}：分页扫待向量化 segment→嵌入写 ES→回写 embeddingId→升 VECTOR_STORED。</li>
 * </ul>
 */
public interface DocumentProcessService {

    /**
     * 上传并解析文档，创建首个版本。
     *
     * @param file       上传文件
     * @param title      文档标题
     * @param category   分类
     * @return 新建知识库 ID
     */
    Long upload(MultipartFile file, String title, String category);

    /**
     * 上传新版本（语义版本号需大于当前最新）。
     *
     * @param docId      知识库 ID
     * @param file       上传文件
     * @param changelog  版本变更说明
     * @return 新版本 ID
     */
    Long uploadNewVersion(Long docId, MultipartFile file, String changelog);

    /**
     * 切块：按切分参数切块并落 segment 表，状态置 CHUNKED，发布 DocumentChunkedEvent。
     *
     * @param docId     知识库 ID
     * @param splitType 切分类型
     * @param chunkSize 块大小
     * @param overlap   重叠
     * @return 分段数
     */
    int split(Long docId, String splitType, Integer chunkSize, Integer overlap);

    /**
     * 向量化指定版本：分页扫 STORED + skipEmbedding=0 + embeddingId IS NULL 的 segment，
     * 嵌入写 ES，回写 embeddingId，升状态 VECTOR_STORED。
     *
     * @param version 版本实体
     */
    void embedAndStore(KnowledgeBaseVersionEntity version);
}
