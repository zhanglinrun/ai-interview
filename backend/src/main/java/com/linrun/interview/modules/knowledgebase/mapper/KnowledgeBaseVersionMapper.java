package com.linrun.interview.modules.knowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseVersionEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface KnowledgeBaseVersionMapper extends BaseMapper<KnowledgeBaseVersionEntity> {

  @Delete("DELETE FROM knowledge_base_version WHERE doc_id = #{docId}")
  int physicalDeleteByDocId(@Param("docId") Long docId);

  @Delete("DELETE FROM knowledge_base_version WHERE version_id = #{versionId}")
  int physicalDeleteByVersionId(@Param("versionId") Long versionId);

  @Update("""
      UPDATE knowledge_base_version
      SET embedding_claimed_at = #{now},
          embedding_attempt = embedding_attempt + 1,
          embedding_last_error = NULL,
          lock_version = lock_version + 1,
          updated_at = #{now}
      WHERE version_id = #{versionId}
        AND status = 'CHUNKED'
        AND embedding_terminal_failure = 0
        AND embedding_attempt < #{maxAttempts}
        AND (embedding_next_retry_at IS NULL OR embedding_next_retry_at <= #{now})
        AND (embedding_claimed_at IS NULL OR embedding_claimed_at < #{expiredBefore})
      """)
  int claimEmbedding(
      @Param("versionId") Long versionId,
      @Param("now") LocalDateTime now,
      @Param("expiredBefore") LocalDateTime expiredBefore,
      @Param("maxAttempts") int maxAttempts
  );

  @Update("""
      UPDATE knowledge_base_version
      SET embedding_claimed_at = #{now},
          lock_version = lock_version + 1,
          updated_at = #{now}
      WHERE version_id = #{versionId}
        AND status = 'CHUNKED'
        AND embedding_attempt = #{attempt}
        AND embedding_claimed_at = #{claimedAt}
      """)
  int renewEmbeddingLease(
      @Param("versionId") Long versionId,
      @Param("attempt") int attempt,
      @Param("claimedAt") LocalDateTime claimedAt,
      @Param("now") LocalDateTime now
  );

  @Update("""
      UPDATE knowledge_base_version
      SET status = 'VECTOR_STORED',
          embedding_claimed_at = NULL,
          embedding_next_retry_at = NULL,
          embedding_last_error = NULL,
          embedding_terminal_failure = 0,
          lock_version = lock_version + 1,
          updated_at = NOW(6)
      WHERE version_id = #{versionId}
        AND status = 'CHUNKED'
        AND embedding_attempt = #{attempt}
        AND embedding_claimed_at = #{claimedAt}
      """)
  int completeEmbedding(
      @Param("versionId") Long versionId,
      @Param("attempt") int attempt,
      @Param("claimedAt") LocalDateTime claimedAt
  );

  @Update("""
      UPDATE knowledge_base_version
      SET embedding_claimed_at = NULL,
          embedding_next_retry_at = #{nextRetryAt},
          embedding_last_error = #{lastError},
          embedding_terminal_failure = #{terminalFailure},
          lock_version = lock_version + 1,
          updated_at = NOW(6)
      WHERE version_id = #{versionId}
        AND status = 'CHUNKED'
        AND embedding_attempt = #{attempt}
        AND embedding_claimed_at = #{claimedAt}
      """)
  int failEmbedding(
      @Param("versionId") Long versionId,
      @Param("attempt") int attempt,
      @Param("claimedAt") LocalDateTime claimedAt,
      @Param("nextRetryAt") LocalDateTime nextRetryAt,
      @Param("lastError") String lastError,
      @Param("terminalFailure") boolean terminalFailure
  );

  @Update("""
      UPDATE knowledge_base_version
      SET embedding_claimed_at = NULL,
          embedding_next_retry_at = NULL,
          embedding_last_error = NULL,
          embedding_terminal_failure = 0,
          embedding_attempt = 0,
          lock_version = lock_version + 1,
          updated_at = NOW(6)
      WHERE version_id = #{versionId}
      """)
  int resetEmbedding(@Param("versionId") Long versionId);

  @Update("""
      UPDATE knowledge_base_version
      SET status = 'CONVERTED',
          lock_version = lock_version + 1,
          updated_at = NOW(6)
      WHERE version_id = #{versionId}
        AND doc_id = #{docId}
        AND status = 'VECTOR_STORED'
        AND deleted = 0
      """)
  int beginRechunk(@Param("versionId") Long versionId, @Param("docId") Long docId);
}
