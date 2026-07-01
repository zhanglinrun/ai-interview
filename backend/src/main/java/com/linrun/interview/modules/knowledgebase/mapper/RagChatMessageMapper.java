package com.linrun.interview.modules.knowledgebase.mapper;

import com.linrun.interview.modules.knowledgebase.model.RagChatMessageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RagChatMessageMapper extends BaseMapper<RagChatMessageEntity> {

  @Select("""
      SELECT COUNT(*) FROM rag_chat_messages m
      INNER JOIN rag_chat_sessions s ON m.session_id = s.id
      WHERE m.type = #{type} AND s.user_id = #{userId}
      """)
  long countByTypeAndSessionUserId(@Param("type") String type, @Param("userId") Long userId);
}
