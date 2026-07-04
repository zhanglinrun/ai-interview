package com.linrun.interview.modules.knowledgebase.service;

/**
 * 文档版本清理服务（对齐业界实践 DocumentCleanupService）。
 *
 * <p>负责清理旧版本数据（ES 向量 + 残留 segment），保留当前版本。
 * 由 {@code deactivateVersion} 和补偿任务调用。
 */
public interface DocumentCleanupService {

    /**
     * 清理指定版本的数据（ES 向量 + 物理删 segment），保留当前版本。
     *
     * @param docId     文档 ID
     * @param versionId 要清理的版本 ID
     * @return 是否清理成功
     */
    boolean cleanupOldVersionData(Long docId, Long versionId);
}
