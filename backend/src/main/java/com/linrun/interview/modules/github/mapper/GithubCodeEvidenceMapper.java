package com.linrun.interview.modules.github.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.modules.github.model.GithubCodeEvidenceEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GithubCodeEvidenceMapper extends BaseMapper<GithubCodeEvidenceEntity> {

  @Insert("""
      <script>
      INSERT INTO github_code_evidence
        (owner_user_id, data_domain, resource_id, resource_version,
         repository_id, commit_sha, path, language, symbol_name, symbol_kind,
         start_line, end_line, parent_summary, content, content_hash, evidence_id,
         source_locator, embedding_id, created_at)
      VALUES
      <foreach collection="chunks" item="chunk" separator=",">
        (#{chunk.ownerUserId}, #{chunk.dataDomain}, #{chunk.resourceId}, #{chunk.resourceVersion},
         #{chunk.repositoryId}, #{chunk.commitSha}, #{chunk.path},
         #{chunk.language}, #{chunk.symbolName}, #{chunk.symbolKind}, #{chunk.startLine},
         #{chunk.endLine}, #{chunk.parentSummary}, #{chunk.content}, #{chunk.contentHash},
         #{chunk.evidenceId}, #{chunk.sourceLocator}, #{chunk.embeddingId}, #{chunk.createdAt})
      </foreach>
      </script>
      """)
  int batchInsert(@Param("chunks") List<GithubCodeEvidenceEntity> chunks);

  @Select("""
      SELECT * FROM github_code_evidence
      WHERE owner_user_id = #{userId} AND repository_id = #{repositoryId}
        AND commit_sha = #{commitSha}
      ORDER BY path, start_line
      LIMIT #{limit}
      """)
  List<GithubCodeEvidenceEntity> selectSnapshot(
      @Param("userId") Long userId,
      @Param("repositoryId") Long repositoryId,
      @Param("commitSha") String commitSha,
      @Param("limit") int limit);

  @Update("""
      <script>
      UPDATE github_code_evidence
      SET embedding_id = CASE evidence_id
      <foreach collection="chunks" item="chunk">
        WHEN #{chunk.evidenceId} THEN #{chunk.embeddingId}
      </foreach>
      END
      WHERE owner_user_id = #{userId} AND evidence_id IN
      <foreach collection="chunks" item="chunk" open="(" separator="," close=")">
        #{chunk.evidenceId}
      </foreach>
      </script>
      """)
  int batchUpdateEmbeddingIds(
      @Param("userId") Long userId,
      @Param("chunks") List<GithubCodeEvidenceEntity> chunks);
}
