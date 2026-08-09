package com.linrun.interview.document.mapper;

import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface KnowledgeBaseEntityMapper extends BaseMapper<KnowledgeBaseEntity> {

  @Select("SELECT DISTINCT category FROM documents WHERE user_id = #{userId} AND category IS NOT NULL")
  List<String> selectCategoriesByUserId(@Param("userId") Long userId);

  @Update({
      "<script>",
      "UPDATE documents SET question_count = question_count + 1,",
      "last_accessed_at = NOW(6) WHERE user_id = #{userId} AND id IN",
      "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
      "</script>"
  })
  int incrementQuestionCountBatch(@Param("userId") Long userId, @Param("ids") List<Long> ids);

  @Update("""
      UPDATE documents
      SET doc_status = 'CONVERTED',
          lock_version = lock_version + 1
      WHERE id = #{docId}
        AND current_version_id = #{versionId}
        AND doc_status = 'VECTOR_STORED'
        AND deleted = 0
      """)
  int beginRechunk(@Param("docId") Long docId, @Param("versionId") Long versionId);

  /** 级联删除时物理删主表，绕过 {@code @TableLogic} 软删。 */
  @Delete("DELETE FROM documents WHERE id = #{id}")
  int physicalDeleteById(@Param("id") Long id);
}
