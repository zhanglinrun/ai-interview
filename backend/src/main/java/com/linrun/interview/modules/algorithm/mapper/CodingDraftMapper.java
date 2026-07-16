package com.linrun.interview.modules.algorithm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.modules.algorithm.model.CodingDraftEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CodingDraftMapper extends BaseMapper<CodingDraftEntity> {

  @Update("""
      UPDATE coding_drafts
      SET source_code = #{sourceCode}, code_hash = #{codeHash},
          revision = revision + 1, updated_at = #{updatedAt}
      WHERE attempt_id = #{attemptId} AND user_id = #{userId} AND revision = #{expectedRevision}
      """)
  int updateOwnedDraft(
      @Param("attemptId") Long attemptId,
      @Param("userId") Long userId,
      @Param("expectedRevision") Integer expectedRevision,
      @Param("sourceCode") String sourceCode,
      @Param("codeHash") String codeHash,
      @Param("updatedAt") LocalDateTime updatedAt);
}
