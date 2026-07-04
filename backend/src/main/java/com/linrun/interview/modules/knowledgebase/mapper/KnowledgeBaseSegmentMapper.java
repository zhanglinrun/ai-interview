package com.linrun.interview.modules.knowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface KnowledgeBaseSegmentMapper extends BaseMapper<KnowledgeBaseSegmentEntity> {

  @Delete("DELETE FROM knowledge_base_segment WHERE document_id = #{docId}")
  int physicalDeleteByDocumentId(@Param("docId") Long docId);

  @Delete("DELETE FROM knowledge_base_segment WHERE document_version = #{versionId}")
  int physicalDeleteByDocumentVersion(@Param("versionId") Long versionId);

  @Update("""
      UPDATE knowledge_base_segment SET status = #{toStatus}, embedding_id = NULL
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
      UPDATE knowledge_base_segment
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
      </script>
      """)
  int batchUpdateEmbedding(
      @Param("segments") List<KnowledgeBaseSegmentEntity> segments,
      @Param("status") String status);
}
