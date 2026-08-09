package com.linrun.interview.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * RAG 聊天消息实体
 * 存储用户问题和 AI 回答
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("chat_messages")
public class RagChatMessageEntity {

  @TableId(type = IdType.AUTO)
  private Long id;

  private Long sessionId;

  @TableField(exist = false)
  private RagChatSessionEntity session;

  private MessageType type;

  private String content;

  private String transformContent;

  private Integer messageOrder;

  private LocalDateTime createdAt;

  private LocalDateTime updatedAt;

  private Boolean completed = true;

  public enum MessageType {
    USER,
    ASSISTANT
  }

  public void setSession(RagChatSessionEntity session) {
    this.session = session;
    this.sessionId = session != null ? session.getId() : null;
  }

  public String getTypeString() {
    return type.name().toLowerCase();
  }
}
