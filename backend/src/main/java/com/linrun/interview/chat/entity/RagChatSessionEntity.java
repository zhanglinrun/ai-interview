package com.linrun.interview.chat.entity;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG 聊天会话实体
 * 一个会话可以关联多个知识库，包含多条消息
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("chat_sessions")
public class RagChatSessionEntity {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long userId;

  private String title;

  private SessionStatus status = SessionStatus.ACTIVE;

  @TableField(exist = false)
  private Set<KnowledgeBaseEntity> knowledgeBases = new HashSet<>();

  @TableField(exist = false)
  private List<RagChatMessageEntity> messages = new ArrayList<>();

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;

  private Integer messageCount = 0;

  private Boolean isPinned = false;

  public enum SessionStatus {
    ACTIVE,
    ARCHIVED
  }

  public void addMessage(RagChatMessageEntity message) {
    messages.add(message);
    message.setSessionId(this.id);
    messageCount = messages.size();
    updatedAt = LocalDateTime.now();
  }

  public List<Long> getKnowledgeBaseIds() {
    return knowledgeBases.stream()
        .map(KnowledgeBaseEntity::getId)
        .toList();
  }
}
