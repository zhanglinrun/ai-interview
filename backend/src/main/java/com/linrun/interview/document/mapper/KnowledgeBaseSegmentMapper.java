package com.linrun.interview.document.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.document.entity.KnowledgeBaseSegmentEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface KnowledgeBaseSegmentMapper extends BaseMapper<KnowledgeBaseSegmentEntity> {

  @Delete("DELETE FROM document_segments WHERE document_id = #{docId}")
  int physicalDeleteByDocumentId(@Param("docId") Long docId);

  @Delete("DELETE FROM document_segments WHERE document_version = #{versionId}")
  int physicalDeleteByDocumentVersion(@Param("versionId") Long versionId);

  @Update("""
      UPDATE document_segments SET status = #{toStatus}, embedding_id = NULL
      WHERE document_id = #{docId} AND document_version = #{versionId} AND status = #{fromStatus}
      """)
  int downgradeStatus(
      @Param("docId") Long docId,
      @Param("versionId") Long versionId,
      @Param("fromStatus") String fromStatus,
      @Param("toStatus") String toStatus);

  /**
   * 向量化每批完成后一条 UPDATE 批量回写 embeddingId + status（消灭循环单条 update）。
   */
  @Update("""
      <script>
      UPDATE document_segments
      SET status = #{status},
          embedding_id = CASE id
          <foreach collection="segments" item="seg">
            WHEN #{seg.id} THEN #{seg.embeddingId}
          </foreach>
          END
      WHERE id IN
      <foreach collection="segments" item="seg" open="(" separator="," close=")">
        #{seg.id}
      </foreach>
        AND document_id = #{docId}
        AND document_version = #{versionId}
        AND status = 'STORED'
        AND embedding_id IS NULL
        AND EXISTS (
          SELECT 1
          FROM documents kb
          JOIN document_versions kbv
            ON kbv.doc_id = kb.id
           AND kbv.version_id = #{versionId}
           AND kbv.deleted = 0
          WHERE kb.id = #{docId}
            AND kb.deleted = 0
            AND kbv.embedding_attempt = #{attempt}
            AND kbv.embedding_claimed_at = #{claimedAt}
        )
      </script>
      """)
  int batchUpdateEmbedding(
      @Param("segments") List<KnowledgeBaseSegmentEntity> segments,
      @Param("docId") Long docId,
      @Param("versionId") Long versionId,
      @Param("attempt") int attempt,
      @Param("claimedAt") java.time.LocalDateTime claimedAt,
      @Param("status") String status);
}
