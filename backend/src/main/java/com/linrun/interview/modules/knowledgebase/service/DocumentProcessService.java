package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.modules.knowledgebase.constant.DocumentAccessScope;
import com.linrun.interview.modules.knowledgebase.model.DocumentSplitParam;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseVersionEntity;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/**
 * 知识库文档处理编排接口（对齐业界实践 DocumentProcessService）。
 *
 * <p>编排上传→解析→切块→向量化的完整链路：
 * <ul>
 *   <li>{@link #upload}：校验→原件存 MinIO→落库文档与版本→解析为 Markdown→状态 CONVERTED。</li>
 *   <li>{@link #split}：切块→落 segment 表→状态 CHUNKED→发布 {@code DocumentChunkedEvent}。</li>
 *   <li>{@link #rechunk}：删当前版本 segment→降状态 CONVERTED→重新 split，用于重新向量化。</li>
 *   <li>{@link #embedAndStore}：委托 {@link KnowledgeDocumentService#activateVersion} 完成向量化。</li>
 *   <li>{@link #switchVersion}：把当前激活版本切换到指定版本（已向量化版本零重建热切换）。</li>
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
     * 上传并解析文档，指定访问范围。
     */
    Long upload(MultipartFile file, String title, String category,
                DocumentAccessScope accessScope, LocalDate expireDate);

    /**
     * 上传新版本（版本号自动递增，旧版本即时失效）。
     *
     * @param docId      知识库 ID
     * @param file       上传文件
     * @param changelog  版本变更说明
     * @return 新版本 ID
     */
    Long uploadNewVersion(Long docId, MultipartFile file, String changelog);

    /**
     * 切块：按 Markdown 标题层级切块并落 segment 表，状态置 CHUNKED，发布 DocumentChunkedEvent。
     *
     * <p>切块固定用 {@code MarkdownHeaderBrotherTextSplitter}，块大小/重叠取自
     * {@code KnowledgeBaseQueryProperties.chunkSizeChars/chunkOverlapChars}，不在此处暴露参数。
     *
     * @param docId 知识库 ID
     * @return 分段数
     */
    int split(Long docId);

    /**
     * 按指定切块策略切块。
     */
    int split(Long docId, DocumentSplitParam splitParam);

    /**
     * 重新切块并重新向量化：删当前版本 segment、降 docStatus 为 CONVERTED，再 split 重新发事件触发异步向量化。
     * 用于「重新向量化」手动重试入口。
     *
     * @param docId 知识库 ID
     * @return 重新切块后的分段数
     */
    int rechunk(Long docId);

    /**
     * 向量化指定版本：委托 {@link KnowledgeDocumentService#activateVersion} 分页嵌入写 ES。
     *
     * @param version 版本实体
     */
    void embedAndStore(KnowledgeBaseVersionEntity version);

    /**
     * 切换当前激活版本到指定版本。
     *
     * <p>已向量化的目标版本直接热切换（仅切 {@code currentVersionId}，ES 按 metadata version 过滤）；
     * 未向量化的目标版本（CHUNKED）先激活向量化再切换。原当前版本降级为 CHUNKED（保留 segment，
     * 清 ES 向量），可再次激活切回。
     *
     * @param docId     知识库 ID
     * @param versionId 目标版本 ID
     */
    void switchVersion(Long docId, Long versionId);
}
