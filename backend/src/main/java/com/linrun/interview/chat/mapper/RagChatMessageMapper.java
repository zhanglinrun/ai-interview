package com.linrun.interview.chat.mapper;

import com.linrun.interview.chat.entity.RagChatMessageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface RagChatMessageMapper extends BaseMapper<RagChatMessageEntity> {

  @Select("""
      SELECT COUNT(*) FROM chat_messages m
      INNER JOIN chat_sessions s ON m.session_id = s.id
      WHERE m.type = #{type} AND s.user_id = #{userId}
      """)
  long countByTypeAndSessionUserId(@Param("type") String type, @Param("userId") Long userId);
}
