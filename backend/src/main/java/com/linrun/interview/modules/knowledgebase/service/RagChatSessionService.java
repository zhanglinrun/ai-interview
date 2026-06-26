package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.infrastructure.mapper.KnowledgeBaseMapper;
import com.linrun.interview.infrastructure.mapper.RagChatMapper;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import com.linrun.interview.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import com.linrun.interview.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import com.linrun.interview.modules.knowledgebase.model.RagChatDTO.SessionDetailDTO;
import com.linrun.interview.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import com.linrun.interview.modules.knowledgebase.model.RagChatMessageEntity;
import com.linrun.interview.modules.knowledgebase.model.RagChatSessionEntity;
import com.linrun.interview.modules.knowledgebase.rag.TitleSummaryService;
import com.linrun.interview.modules.knowledgebase.repository.KnowledgeBaseRepository;
import com.linrun.interview.modules.knowledgebase.repository.RagChatMessageRepository;
import com.linrun.interview.modules.knowledgebase.repository.RagChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatSessionService {

    private final RagChatSessionRepository sessionRepository;
    private final RagChatMessageRepository messageRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseQueryService queryService;
    private final RagChatMapper ragChatMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseQueryProperties queryProperties;
    private final TitleSummaryService titleSummaryService;

    @Transactional
    public SessionDTO createSession(CreateSessionRequest request) {
        Long userId = UserContext.requireUserId();
        List<Long> knowledgeBaseIds = request.knowledgeBaseIds() != null
            ? request.knowledgeBaseIds()
            : List.of();
        List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository
            .findAllByUserIdAndIdIn(userId, knowledgeBaseIds);

        if (knowledgeBases.size() != knowledgeBaseIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分知识库不存在");
        }

        RagChatSessionEntity session = new RagChatSessionEntity();
        session.setUserId(userId);
        session.setTitle(request.title() != null && !request.title().isBlank()
            ? request.title()
            : generateTitle(knowledgeBases));
        session.setKnowledgeBases(new HashSet<>(knowledgeBases));

        session = sessionRepository.save(session);
        log.info("创建 RAG 聊天会话: id={}, title={}", session.getId(), session.getTitle());
        return ragChatMapper.toSessionDTO(session);
    }

    public List<SessionListItemDTO> listSessions() {
        return sessionRepository.findAllByUserIdOrderByPinnedAndUpdatedAtDesc(
                UserContext.requireUserId())
            .stream()
            .map(ragChatMapper::toSessionListItemDTO)
            .toList();
    }

    public SessionDetailDTO getSessionDetail(Long sessionId) {
        RagChatSessionEntity session = sessionRepository
            .findByUserIdAndIdWithKnowledgeBases(UserContext.requireUserId(), sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        List<RagChatMessageEntity> messages = messageRepository
            .findBySessionIdOrderByMessageOrderAsc(sessionId);
        List<KnowledgeBaseListItemDTO> kbDTOs = knowledgeBaseMapper.toListItemDTOList(
            new ArrayList<>(session.getKnowledgeBases())
        );

        return ragChatMapper.toSessionDetailDTO(session, messages, kbDTOs);
    }

    @Transactional
    public Long prepareStreamMessage(Long sessionId, String question) {
        RagChatSessionEntity session = sessionRepository.findByUserIdAndIdWithKnowledgeBases(
                UserContext.requireUserId(), sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        int nextOrder = session.getMessageCount();

        RagChatMessageEntity userMessage = new RagChatMessageEntity();
        userMessage.setSession(session);
        userMessage.setType(RagChatMessageEntity.MessageType.USER);
        userMessage.setContent(question);
        userMessage.setMessageOrder(nextOrder);
        userMessage.setCompleted(true);
        messageRepository.save(userMessage);

        RagChatMessageEntity assistantMessage = new RagChatMessageEntity();
        assistantMessage.setSession(session);
        assistantMessage.setType(RagChatMessageEntity.MessageType.ASSISTANT);
        assistantMessage.setContent("");
        assistantMessage.setMessageOrder(nextOrder + 1);
        assistantMessage.setCompleted(false);
        assistantMessage = messageRepository.save(assistantMessage);

        session.setMessageCount(nextOrder + 2);
        sessionRepository.save(session);

        log.info("准备流式消息: sessionId={}, messageId={}", sessionId, assistantMessage.getId());
        return assistantMessage.getId();
    }

    @Transactional
    public void completeStreamMessage(Long messageId, String content) {
        RagChatMessageEntity message = messageRepository.findById(messageId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "消息不存在"));

        message.setContent(content);
        message.setCompleted(true);
        messageRepository.save(message);

        log.info("完成流式消息: messageId={}, contentLength={}",
            messageId, content != null ? content.length() : 0);
    }

    /**
     * 异步 LLM 标题生成（亮点6）。
     *
     * <p>仅当 {@code app.ai.rag.title-summary.enabled=true} 且当前为会话首问（用户消息数 == 1）时，
     * 用虚拟线程异步调 {@link TitleSummaryService#generateTitle(String)} 根据首问内容生成摘要标题，
     * 更新会话标题。失败只 warn，保留原规则标题（知识库名 / "N 个知识库对话"），不抛异常。
     *
     * <p>复用 {@code LlmProviderRegistry.getDefaultChatModel()} 构造的 {@link TitleSummaryService}
     * bean（弃 know-engine 每次 {@code new OpenAiChatModel}）。在流式首问完成后调用，不阻塞响应。
     *
     * @param sessionId 会话 ID
     * @param firstQuestion 首问内容（用于生成标题）
     */
    public void maybeGenerateTitleAsync(Long sessionId, String firstQuestion) {
        if (!queryProperties.getTitleSummary().isEnabled()) {
            return;
        }
        if (firstQuestion == null || firstQuestion.isBlank()) {
            return;
        }
        long userMsgCount = messageRepository.countBySessionIdAndType(sessionId,
            RagChatMessageEntity.MessageType.USER);
        if (userMsgCount != 1) {
            return;
        }
        Thread.ofVirtual().name("title-summary-" + sessionId).start(() -> {
            try {
                String aiTitle = titleSummaryService.generateTitle(firstQuestion);
                if (aiTitle == null || aiTitle.isBlank()) {
                    log.warn("[RagChatSessionService] LLM 生成标题为空，保留原标题: sessionId={}", sessionId);
                    return;
                }
                String trimmed = aiTitle.trim().replaceAll("^\"|\"$", "");
                if (trimmed.isBlank()) {
                    return;
                }
                // 虚拟线程无请求 ThreadLocal，sessionId 已在请求线程校验归属，直接按 id 更新
                sessionRepository.findById(sessionId).ifPresent(session -> {
                    session.setTitle(trimmed);
                    sessionRepository.save(session);
                    log.info("[RagChatSessionService] LLM 生成会话标题: sessionId={}, title={}",
                        sessionId, trimmed);
                });
            } catch (Exception e) {
                log.warn("[RagChatSessionService] LLM 生成标题失败，保留原标题: sessionId={}, error={}",
                    sessionId, e.getMessage(), e);
            }
        });
    }

    public Flux<String> getStreamAnswer(Long sessionId, String question, Long assistantMessageId) {
        RagChatSessionEntity session = sessionRepository.findByUserIdAndIdWithKnowledgeBases(
                UserContext.requireUserId(), sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        List<Long> kbIds = session.getKnowledgeBaseIds();
        List<ChatMessage> history = queryProperties.getHistory().isEnabled()
            ? loadHistoryMessages(sessionId)
            : List.of();

        log.info("加载历史上下文: sessionId={}, historySize={}", sessionId, history.size());
        return queryService.answerQuestionStream(kbIds, question, history, assistantMessageId);
    }

    @Transactional
    public void updateSessionTitle(Long sessionId, String title) {
        RagChatSessionEntity session = sessionRepository.findByUserIdAndId(
                UserContext.requireUserId(), sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        session.setTitle(title);
        sessionRepository.save(session);
        log.info("更新会话标题: sessionId={}, title={}", sessionId, title);
    }

    @Transactional
    public void togglePin(Long sessionId) {
        RagChatSessionEntity session = sessionRepository.findByUserIdAndId(
                UserContext.requireUserId(), sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        Boolean currentPinned = session.getIsPinned() != null ? session.getIsPinned() : false;
        session.setIsPinned(!currentPinned);
        sessionRepository.save(session);
        log.info("切换会话置顶状态: sessionId={}, isPinned={}", sessionId, session.getIsPinned());
    }

    @Transactional
    public void updateSessionKnowledgeBases(Long sessionId, List<Long> knowledgeBaseIds) {
        Long userId = UserContext.requireUserId();
        List<Long> ids = knowledgeBaseIds != null ? knowledgeBaseIds : List.of();

        RagChatSessionEntity session = sessionRepository.findByUserIdAndId(userId, sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "会话不存在"));

        List<KnowledgeBaseEntity> knowledgeBases = knowledgeBaseRepository
            .findAllByUserIdAndIdIn(userId, ids);
        if (knowledgeBases.size() != ids.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分知识库不存在");
        }

        session.setKnowledgeBases(new HashSet<>(knowledgeBases));
        sessionRepository.save(session);
        log.info("更新会话知识库: sessionId={}, kbIds={}", sessionId, ids);
    }

    @Transactional
    public void deleteSession(Long sessionId) {
        Long userId = UserContext.requireUserId();
        if (!sessionRepository.existsByUserIdAndId(userId, sessionId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        sessionRepository.deleteById(sessionId);
        log.info("删除会话: sessionId={}", sessionId);
    }

    private List<ChatMessage> loadHistoryMessages(Long sessionId) {
        int limit = queryProperties.getHistory().getMaxMessages() + 1;
        List<RagChatMessageEntity> recent = messageRepository
            .findRecentCompletedBySessionId(sessionId, PageRequest.of(0, limit));

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
            return "新对话";
        }
        if (knowledgeBases.size() == 1) {
            return knowledgeBases.getFirst().getName();
        }
        return knowledgeBases.size() + " 个知识库对话";
    }
}
