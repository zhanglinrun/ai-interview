package com.linrun.interview.modules.knowledgebase.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RagSessionKnowledgeBaseMapper {

  @Insert("INSERT INTO rag_session_knowledge_bases (session_id, knowledge_base_id) VALUES (#{sessionId}, #{knowledgeBaseId})")
  int insertLink(@Param("sessionId") Long sessionId, @Param("knowledgeBaseId") Long knowledgeBaseId);

  @Delete("DELETE FROM rag_session_knowledge_bases WHERE session_id = #{sessionId}")
  int deleteBySessionId(@Param("sessionId") Long sessionId);

  @Delete("DELETE FROM rag_session_knowledge_bases WHERE session_id = #{sessionId} AND knowledge_base_id = #{knowledgeBaseId}")
  int deleteLink(@Param("sessionId") Long sessionId, @Param("knowledgeBaseId") Long knowledgeBaseId);

  @Select("SELECT session_id FROM rag_session_knowledge_bases WHERE knowledge_base_id = #{knowledgeBaseId}")
  List<Long> selectSessionIdsByKnowledgeBaseId(@Param("knowledgeBaseId") Long knowledgeBaseId);

  @Select({
      "<script>",
      "SELECT DISTINCT session_id FROM rag_session_knowledge_bases",
      "WHERE knowledge_base_id IN",
      "<foreach collection='knowledgeBaseIds' item='id' open='(' separator=',' close=')'>",
      "#{id}",
      "</foreach>",
      "</script>"
  })
  List<Long> selectSessionIdsByKnowledgeBaseIds(@Param("knowledgeBaseIds") List<Long> knowledgeBaseIds);

  @Select("SELECT knowledge_base_id FROM rag_session_knowledge_bases WHERE session_id = #{sessionId}")
  List<Long> selectKnowledgeBaseIdsBySessionId(@Param("sessionId") Long sessionId);
}
