package com.linrun.interview.modules.knowledgebase.mapper;

import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface KnowledgeBaseEntityMapper extends BaseMapper<KnowledgeBaseEntity> {

  @Select("SELECT DISTINCT category FROM knowledge_bases WHERE user_id = #{userId} AND category IS NOT NULL")
  List<String> selectCategoriesByUserId(@Param("userId") Long userId);

  @Update({
      "<script>",
      "UPDATE knowledge_bases SET question_count = question_count + 1,",
      "last_accessed_at = NOW(6) WHERE user_id = #{userId} AND id IN",
      "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
      "</script>"
  })
  int incrementQuestionCountBatch(@Param("userId") Long userId, @Param("ids") List<Long> ids);
}