package com.linrun.interview.chat.service;
import com.linrun.interview.chat.dto.RagChatDTO;
import com.linrun.interview.document.service.KnowledgeBaseListService;
import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;
import com.linrun.interview.rag.service.KnowledgeBaseQueryService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.infra.persistence.EntityQueries;
import com.linrun.interview.infra.persistence.MapperUtils;
import com.linrun.interview.infra.observability.TraceContext;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.document.converter.KnowledgeBaseMapper;
import com.linrun.interview.chat.converter.RagChatConverter;
import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.chat.mapper.RagChatMessageMapper;
import com.linrun.interview.chat.mapper.RagChatSessionMapper;
import com.linrun.interview.chat.mapper.RagSessionKnowledgeBaseMapper;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.linrun.interview.document.vo.KnowledgeBaseListItemDTO;
import com.linrun.interview.chat.dto.RagChatDTO.CreateSessionRequest;
import com.linrun.interview.chat.dto.RagChatDTO.SessionDTO;
import com.linrun.interview.chat.dto.RagChatDTO.SessionDetailDTO;
import com.linrun.interview.chat.dto.RagChatDTO.SessionListItemDTO;
import com.linrun.interview.chat.entity.RagChatMessageEntity;
import com.linrun.interview.chat.entity.RagChatSessionEntity;
import com.linrun.interview.ai.service.TitleSummaryService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatSessionService {

  private final RagChatSessionMapper sessionMapper;
  private final RagChatMessageMapper messageMapper;
  private final KnowledgeBaseEntityMapper knowledgeBaseEntityMapper;
  private final RagSessionKnowledgeBaseMapper sessionKnowledgeBaseMapper;
  private final KnowledgeBaseListService listService;
  private final KnowledgeBaseQueryService queryService;
  private final RagChatConverter ragChatConverter;
  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final KnowledgeBaseQueryProperties queryProperties;
  private final TitleSummaryService titleSummaryService;

  @Transactional
  public SessionDTO createSession(CreateSessionRequest request) {
    Long userId = UserContext.requireUserId();
    List<Long> knowledgeBaseIds = request.knowledgeBaseIds() != null
      ? request.knowledgeBaseIds()
      : List.of();
    List<KnowledgeBaseEntity> knowledgeBases = loadKnowledgeBases(userId, knowledgeBaseIds);
    if (knowledgeBases.size() != knowledgeBaseIds.size()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "部分知识库不存在");
    }

    RagChatSessionEntity session = new RagChatSessionEntity();
    session.setUserId(userId);
    session.setTitle(request.title() != null && !request.title().isBlank()
      ? request.title()
      : generateTitle(knowledgeBases));
    session.setCreatedAt(LocalDateTime.now());
    session.setUpdatedAt(LocalDateTime.now());
    session = MapperUtils.save(sessionMapper, session);
    saveKnowledgeBaseLinks(session.getId(), knowledgeBaseIds);
    session.setKnowledgeBases(new HashSet<>(knowledgeBases));

    log.info("创建 RAG 会话成功: id={}, title={}", session.getId(), session.getTitle());
    return ragChatConverter.toSessionDTO(session);
  }

  public List<SessionListItemDTO> listSessions() {
    Long userId = UserContext.requireUserId();
    List<RagChatSessionEntity> sessions = sessionMapper.selectList(
      Wrappers.<RagChatSessionEntity>lambdaQuery()
        .eq(RagChatSessionEntity::getUserId, userId)
        .orderByDesc(RagChatSessionEntity::getIsPinned)
        .orderByDesc(RagChatSessionEntity::getUpdatedAt));
    for (RagChatSessionEntity session : sessions) {
      attachKnowledgeBases(session);
    }
    return sessions.stream().map(ragChatConverter::toSessionListItemDTO).toList();
  }

  public SessionDetailDTO getSessionDetail(Long sessionId) {
    RagChatSessionEntity session = requireSessionWithKnowledgeBases(UserContext.requireUserId(), sessionId);
    List<RagChatMessageEntity> messages = listMessages(sessionId);
    List<KnowledgeBaseListItemDTO> kbDTOs = knowledgeBaseMapper.toListItemDTOList(
      new ArrayList<>(session.getKnowledgeBases()));
    return ragChatConverter.toSessionDetailDTO(session, messages, kbDTOs);
  }

  @Transactional
  public Long prepareStreamMessage(Long sessionId, String question) {
    RagChatSessionEntity session = requireSessionWithKnowledgeBases(UserContext.requireUserId(), sessionId);
    int nextOrder = session.getMessageCount() != null ? session.getMessageCount() : 0;

    RagChatMessageEntity userMessage = new RagChatMessageEntity();
    userMessage.setSessionId(sessionId);
    userMessage.setType(RagChatMessageEntity.MessageType.USER);
    userMessage.setContent(question);
    userMessage.setMessageOrder(nextOrder);
    userMessage.setCompleted(true);
    userMessage.setCreatedAt(LocalDateTime.now());
    MapperUtils.save(messageMapper, userMessage);

    RagChatMessageEntity assistantMessage = new RagChatMessageEntity();
    assistantMessage.setSessionId(sessionId);
    assistantMessage.setType(RagChatMessageEntity.MessageType.ASSISTANT);
    assistantMessage.setContent("");
    assistantMessage.setMessageOrder(nextOrder + 1);
    assistantMessage.setCompleted(false);
    assistantMessage.setCreatedAt(LocalDateTime.now());
    assistantMessage = MapperUtils.save(messageMapper, assistantMessage);

    session.setMessageCount(nextOrder + 2);
    session.setUpdatedAt(LocalDateTime.now());
    MapperUtils.save(sessionMapper, session);

    log.info("准备流式消息: sessionId={}, messageId={}", sessionId, assistantMessage.getId());
    return assistantMessage.getId();
  }

  @Transactional
  public void completeStreamMessage(Long messageId, String content) {
    RagChatMessageEntity message = Optional.ofNullable(messageMapper.selectById(messageId))
      .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "消息不存在"));
    message.setContent(content);
    message.setCompleted(true);
    message.setUpdatedAt(LocalDateTime.now());
    MapperUtils.save(messageMapper, message);
    log.info("完成流式消息: messageId={}, contentLength={}",
        messageId, content != null ? content.length() : 0);
  }

  public void maybeGenerateTitleAsync(Long sessionId, String firstQuestion) {
    if (!queryProperties.getTitleSummary().isEnabled()) {
      return;
    }
    if (firstQuestion == null || firstQuestion.isBlank()) {
      return;
    }
    long userMsgCount = messageMapper.selectCount(
      Wrappers.<RagChatMessageEntity>lambdaQuery()
        .eq(RagChatMessageEntity::getSessionId, sessionId)
        .eq(RagChatMessageEntity::getType, RagChatMessageEntity.MessageType.USER));
    if (userMsgCount != 1) {
      return;
    }
    Thread.ofVirtual().name("title-summary-" + sessionId).start(() -> {
      try {
        String aiTitle = titleSummaryService.generateTitle(firstQuestion);
        if (aiTitle == null || aiTitle.isBlank()) {
          return;
        }
        String trimmed = aiTitle.trim().replaceAll("^\"|\"$", "");
        if (trimmed.isBlank()) {
          return;
        }
        Optional.ofNullable(sessionMapper.selectById(sessionId)).ifPresent(session -> {
          session.setTitle(trimmed);
          session.setUpdatedAt(LocalDateTime.now());
          MapperUtils.save(sessionMapper, session);
          log.info("[RagChatSessionService] LLM 生成会话标题: sessionId={}, title={}", sessionId, trimmed);
        });
      } catch (Exception e) {
        log.warn("[RagChatSessionService] LLM 生成标题失败: sessionId={}, error={}",
            sessionId, e.getMessage(), e);
      }
    });
  }

  public Flux<String> getStreamAnswer(Long sessionId, String question, Long assistantMessageId) {
    // Legacy/raw SSE keeps the same request trace for the asynchronous RAG
    // sink; callers outside HTTP still get the old generated-trace behavior.
    return getStreamAnswer(sessionId, question, assistantMessageId,
        TraceContext.getTraceId());
  }

  /** 结构化 SSE 调用传入同一个 traceId，使流事件和持久化 Trace 对齐。 */
  public Flux<String> getStreamAnswer(Long sessionId, String question, Long assistantMessageId,
                                      String traceId) {
    RagChatSessionEntity session = requireSessionWithKnowledgeBases(UserContext.requireUserId(), sessionId);
    List<Long> kbIds = session.getKnowledgeBaseIds();
    List<ChatMessage> history = queryProperties.getHistory().isEnabled()
      ? loadHistoryMessages(sessionId)
      : List.of();
    log.info("流式问答开始: sessionId={}, historySize={}", sessionId, history.size());
    if (traceId == null || traceId.isBlank()) {
      return queryService.answerQuestionStream(kbIds, question, history, assistantMessageId,
          String.valueOf(sessionId));
    }
    return queryService.answerQuestionStream(kbIds, question, history, assistantMessageId,
        String.valueOf(sessionId), traceId);
  }

  @Transactional
  public void updateSessionTitle(Long sessionId, String title) {
    RagChatSessionEntity session = requireSession(UserContext.requireUserId(), sessionId);
    session.setTitle(title);
    session.setUpdatedAt(LocalDateTime.now());
    MapperUtils.save(sessionMapper, session);
    log.info("更新会话标题: sessionId={}, title={}", sessionId, title);
  }

  @Transactional
  public void togglePin(Long sessionId) {
    RagChatSessionEntity session = requireSession(UserContext.requireUserId(), sessionId);
    Boolean currentPinned = session.getIsPinned() != null ? session.getIsPinned() : false;
    session.setIsPinned(!currentPinned);
    session.setUpdatedAt(LocalDateTime.now());
    MapperUtils.save(sessionMapper, session);
    log.info("切换会话置顶状态: sessionId={}, isPinned={}", sessionId, session.getIsPinned());
  }

  @Transactional
  public void updateSessionKnowledgeBases(Long sessionId, List<Long> knowledgeBaseIds) {
    Long userId = UserContext.requireUserId();
    List<Long> ids = knowledgeBaseIds != null ? knowledgeBaseIds : List.of();
    requireSession(userId, sessionId);
    List<KnowledgeBaseEntity> knowledgeBases = loadKnowledgeBases(userId, ids);
    if (knowledgeBases.size() != ids.size()) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "部分知识库不存在");
    }
    sessionKnowledgeBaseMapper.deleteBySessionId(sessionId);
    saveKnowledgeBaseLinks(sessionId, ids);
    log.info("更新会话知识库: sessionId={}, kbIds={}", sessionId, ids);
  }

  @Transactional
  public void deleteSession(Long sessionId) {
    Long userId = UserContext.requireUserId();
    requireSession(userId, sessionId);
    messageMapper.delete(Wrappers.<RagChatMessageEntity>lambdaQuery()
      .eq(RagChatMessageEntity::getSessionId, sessionId));
    sessionKnowledgeBaseMapper.deleteBySessionId(sessionId);
    sessionMapper.deleteById(sessionId);
    log.info("删除会话: sessionId={}", sessionId);
  }

  private RagChatSessionEntity requireSession(Long userId, Long sessionId) {
    return EntityQueries.byUserAndId(sessionMapper, userId, sessionId,
        RagChatSessionEntity::getUserId, RagChatSessionEntity::getId)
      .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));
  }

  private RagChatSessionEntity requireSessionWithKnowledgeBases(Long userId, Long sessionId) {
    RagChatSessionEntity session = requireSession(userId, sessionId);
    attachKnowledgeBases(session);
    return session;
  }

  private void attachKnowledgeBases(RagChatSessionEntity session) {
    List<Long> kbIds = sessionKnowledgeBaseMapper.selectKnowledgeBaseIdsBySessionId(session.getId());
    if (kbIds.isEmpty()) {
      session.setKnowledgeBases(Set.of());
      return;
    }
    session.setKnowledgeBases(new HashSet<>(loadKnowledgeBases(session.getUserId(), kbIds)));
  }

  private List<KnowledgeBaseEntity> loadKnowledgeBases(Long userId, List<Long> ids) {
    return listService.listReadableByIds(userId, ids);
  }

  private void saveKnowledgeBaseLinks(Long sessionId, List<Long> knowledgeBaseIds) {
    for (Long kbId : knowledgeBaseIds) {
      sessionKnowledgeBaseMapper.insertLink(sessionId, kbId);
    }
  }

  private List<RagChatMessageEntity> listMessages(Long sessionId) {
    return messageMapper.selectList(
      Wrappers.<RagChatMessageEntity>lambdaQuery()
        .eq(RagChatMessageEntity::getSessionId, sessionId)
        .orderByAsc(RagChatMessageEntity::getMessageOrder));
  }

  private List<ChatMessage> loadHistoryMessages(Long sessionId) {
    int limit = queryProperties.getHistory().getMaxMessages() + 1;
    List<RagChatMessageEntity> recent = messageMapper.selectList(
      Wrappers.<RagChatMessageEntity>lambdaQuery()
        .eq(RagChatMessageEntity::getSessionId, sessionId)
        .eq(RagChatMessageEntity::getCompleted, true)
        .orderByDesc(RagChatMessageEntity::getMessageOrder)
        .last("LIMIT " + limit));

    if (recent.isEmpty()) {
      return List.of();
    }
    List<RagChatMessageEntity> historyMessages = recent.size() <= 1
      ? List.of()
      : recent.subList(1, recent.size());

    return historyMessages.reversed().stream()
      .map(m -> m.getType() == RagChatMessageEntity.MessageType.USER
        ? (ChatMessage) UserMessage.from(m.getContent())
        : (ChatMessage) AiMessage.from(m.getContent()))
      .toList();
  }

  private String generateTitle(List<KnowledgeBaseEntity> knowledgeBases) {
    if (knowledgeBases.isEmpty()) {
      return "新会话";
    }
    if (knowledgeBases.size() == 1) {
      return knowledgeBases.getFirst().getName();
    }
    return knowledgeBases.size() + " 个知识库的会话";
  }
}
