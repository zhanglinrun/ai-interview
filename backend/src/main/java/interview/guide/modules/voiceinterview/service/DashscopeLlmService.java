package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
@Slf4j
@RequiredArgsConstructor
public class DashscopeLlmService {

    private static final String TERMINAL_PUNCTUATION = "。！？；!?;.";

    private final LlmProviderRegistry llmProviderRegistry;
    private final VoiceInterviewPromptService promptService;
    private final ResumeRepository resumeRepository;
    private final VoiceInterviewProperties voiceInterviewProperties;
    private final PromptSanitizer promptSanitizer;

    public String chat(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
        try {
            PromptContext promptContext = buildPromptContext(userInput, session, conversationHistory);

            String provider = session.getLlmProvider();
            log.info("[VoiceInterview] Session {} using LLM provider: {}", session.getId(), provider);

            ChatModel chatModel = llmProviderRegistry.getChatModelOrDefault(provider);

            String content = chatModel.chat(ChatRequest.builder()
                .messages(SystemMessage.from(promptContext.systemPrompt()),
                          UserMessage.from(promptContext.userPrompt()))
                .build())
                .aiMessage().text();
            String optimized = optimizeForVoice(content);

            log.info("LLM response generated for session {}: {}", session.getId(),
                     optimized.substring(0, Math.min(100, optimized.length())));

            return optimized;

        } catch (Exception e) {
            log.error("LLM chat error for session {}: {}", session.getId(), e.getMessage(), e);
            return mapLlmErrorToUserMessage(e);
        }
    }

    public String chatStream(String userInput, Consumer<String> onToken, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
        return chatStreamSentences(userInput, onToken, null, session, conversationHistory);
    }

    /**
     * 流式调用 LLM，每检测到一个完整句子就回调 onSentence，同时推送实时文本给 onToken。
     * 返回完整优化后的文本。
     */
    public String chatStreamSentences(String userInput,
                                       Consumer<String> onToken,
                                       Consumer<String> onSentence,
                                       VoiceInterviewSessionEntity session,
                                       List<String> conversationHistory) {
        try {
            PromptContext promptContext = buildPromptContext(userInput, session, conversationHistory);
            String provider = session.getLlmProvider();
            log.info("[VoiceInterview] Session {} using LLM provider (sentence stream): {}", session.getId(), provider);

            // TODO 阶段8：换 StreamingChatModel + Flux.create 桥接恢复真流式（SafeGuardStreamingChatModel）
            // 当前临时退化为同步调用，保留 onSentence/onToken 回调在完整文本上切句推送
            ChatModel chatModel = llmProviderRegistry.getChatModelOrDefault(provider);
            StringBuilder raw = new StringBuilder();
            AtomicInteger lastSentenceEnd = new AtomicInteger(0);

            String content = chatModel.chat(ChatRequest.builder()
                .messages(SystemMessage.from(promptContext.systemPrompt()),
                          UserMessage.from(promptContext.userPrompt()))
                .build())
                .aiMessage().text();
            raw.append(content == null ? "" : content);

            // 在完整文本上切句子回调（流式语义降级：不再逐句实时，而是一次性切分）
            if (onSentence != null) {
                String normalized = normalizeRealtimeText(raw.toString());
                int currentEnd = normalized.length();
                if (currentEnd > lastSentenceEnd.get()) {
                    String sentence = normalized.substring(lastSentenceEnd.get()).trim();
                    if (!sentence.isEmpty()) {
                        onSentence.accept(sentence);
                    }
                    lastSentenceEnd.set(currentEnd);
                }
            }

            // 发送最后一段（可能不以终止标点结尾）
            if (onSentence != null) {
                String normalized = normalizeRealtimeText(raw.toString());
                if (normalized.length() > lastSentenceEnd.get()) {
                    String remaining = normalized.substring(lastSentenceEnd.get()).trim();
                    if (!remaining.isEmpty()) {
                        onSentence.accept(remaining);
                    }
                }
            }

            String optimized = optimizeForVoice(raw.toString());
            if (onToken != null && !optimized.isBlank()) {
                onToken.accept(optimized);
            }

            log.info("LLM sentence stream response for session {}: {}", session.getId(),
                optimized.substring(0, Math.min(100, optimized.length())));
            return optimized;
        } catch (Exception e) {
            log.error("LLM sentence stream error for session {}: {}", session.getId(), e.getMessage(), e);
            return mapLlmErrorToUserMessage(e);
        }
    }

    private PromptContext buildPromptContext(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
        String resumeText = null;
        if (session.getResumeId() != null) {
            ResumeEntity resume = resumeRepository.findByUserIdAndId(
                session.getUserId(), session.getResumeId()).orElse(null);
            if (resume != null) {
                resumeText = resume.getResumeText();
            }
        }

        String systemPrompt = promptService.generateSystemPromptWithContext(session.getSkillId(), resumeText);

        StringBuilder promptBuilder = new StringBuilder();
        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            promptBuilder.append("【之前的对话】\n");
            for (String message : conversationHistory) {
                promptBuilder.append(promptSanitizer.sanitize(message)).append("\n");
            }
            promptBuilder.append("\n【当前对话】\n");
        }
        promptBuilder.append("用户：").append(
            promptSanitizer.wrapWithDelimiters("input", promptSanitizer.sanitize(userInput)));
        return new PromptContext(systemPrompt, promptBuilder.toString());
    }

    private String mapLlmErrorToUserMessage(Exception e) {
        String errorMessage = e.getMessage();
        if (errorMessage != null) {
            if (errorMessage.contains("403") || errorMessage.contains("ACCESS_DENIED") ||
                errorMessage.contains("Authentication")) {
                return "AI 服务认证失败，请检查 API Key 配置";
            } else if (errorMessage.contains("timeout") || errorMessage.contains("Timeout")) {
                return "AI 服务响应超时，请稍后重试";
            } else if (errorMessage.contains("429") || errorMessage.contains("rate limit") ||
                       errorMessage.contains("quota")) {
                return "AI 服务调用频率超限，请稍后重试";
            } else if (errorMessage.contains("connection") || errorMessage.contains("network")) {
                return "AI 服务网络连接失败，请检查网络";
            }
        }
        return "抱歉，AI 服务暂时不可用，请稍后重试";
    }

    private String optimizeForVoice(String content) {
        String normalized = normalizeRealtimeText(content);
        if (normalized.isBlank()) {
            return "请继续。";
        }

        int maxChars = Math.max(80, voiceInterviewProperties.getAiQuestionMaxChars());
        if (normalized.length() <= maxChars) {
            return normalized;
        }

        String truncated = normalized.substring(0, maxChars);
        int lastTerminal = -1;
        for (int i = truncated.length() - 1; i >= 0; i--) {
            if (TERMINAL_PUNCTUATION.indexOf(truncated.charAt(i)) >= 0) {
                lastTerminal = i;
                break;
            }
        }
        if (lastTerminal >= maxChars / 2) {
            return truncated.substring(0, lastTerminal + 1);
        }

        return truncated + "…";
    }

    private String normalizeRealtimeText(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content
            .replace("**", "")
            .replace("```", "")
            .replace("`", "")
            .replaceAll("(?m)^\\s*[-*+]\\s*", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private record PromptContext(String systemPrompt, String userPrompt) {}
}
