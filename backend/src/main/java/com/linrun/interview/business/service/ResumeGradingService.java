package com.linrun.interview.business.service;

import com.linrun.interview.ai.service.LlmProviderRegistry;
import com.linrun.interview.ai.service.PromptTemplate;
import com.linrun.interview.ai.service.StructuredOutputInvoker;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.business.vo.ResumeAnalysisResponse;
import com.linrun.interview.business.vo.ResumeAnalysisResponse.ScoreDetail;
import com.linrun.interview.business.vo.ResumeAnalysisResponse.Suggestion;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历评分服务
 * 使用 LangChain4j 调用 LLM 对简历进行评分和建议
 */
@Service
public class ResumeGradingService {
    
    private static final Logger log = LoggerFactory.getLogger(ResumeGradingService.class);
    
    private final LlmProviderRegistry llmProviderRegistry;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final StructuredOutputInvoker structuredOutputInvoker;
    
    // 中间DTO用于接收AI响应
    private record ResumeAnalysisResponseDTO(
        int overallScore,
        ScoreDetailDTO scoreDetail,
        String summary,
        List<String> strengths,
        List<SuggestionDTO> suggestions
    ) {}
    
    private record ScoreDetailDTO(
        int contentScore,
        int structureScore,
        int skillMatchScore,
        int expressionScore,
        int projectScore
    ) {}
    
    private record SuggestionDTO(
        String category,
        String priority,
        String issue,
        String recommendation
    ) {}
    
    public ResumeGradingService(
            LlmProviderRegistry llmProviderRegistry,
            StructuredOutputInvoker structuredOutputInvoker,
            ResumeAnalysisProperties properties,
            ResourceLoader resourceLoader) throws IOException {
        this.llmProviderRegistry = llmProviderRegistry;
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.systemPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(properties.getSystemPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
        this.userPromptTemplate = new PromptTemplate(
            resourceLoader.getResource(properties.getUserPromptPath())
                .getContentAsString(StandardCharsets.UTF_8)
        );
    }
    
    /**
     * 分析简历并返回评分和建议
     *
     * @param resumeText 简历文本内容
     * @param userId     简历所属用户 ID（BYOK 路由，异步消费者从简历实体恢复后传入）
     * @return 分析结果
     */
    public ResumeAnalysisResponse analyzeResume(String resumeText, Long userId) {
        log.info("开始分析简历，文本长度: {} 字符", resumeText.length());
        
        try {
            // 加载系统提示词
            String systemPrompt = systemPromptTemplate.render();
            
            // 加载用户提示词并填充变量
            Map<String, Object> variables = new HashMap<>();
            variables.put("resumeText", resumeText);
            String userPrompt = userPromptTemplate.render(variables);

            // system prompt 的 JSON 结构约束由 StructuredOutputInvoker 通过 responseSchema 传入
            String systemPromptWithFormat = systemPrompt;

            // 调用AI
            ResumeAnalysisResponseDTO dto;
            try {
                ChatModel chatModel = llmProviderRegistry.getUserChatModel(userId);
                dto = structuredOutputInvoker.invoke(
                    chatModel,
                    systemPromptWithFormat,
                    userPrompt,
                    ResumeAnalysisResponseDTO.class,
                    ErrorCode.RESUME_ANALYSIS_FAILED,
                    "简历分析失败：",
                    "简历分析",
                    log
                );
                log.debug("AI响应解析成功: overallScore={}", dto.overallScore());
            } catch (Exception e) {
                log.error("简历分析AI调用失败: {}", e.getMessage(), e);
                throw new BusinessException(
                    ErrorCode.RESUME_ANALYSIS_FAILED,
                    "简历分析失败：" + e.getMessage(),
                    e
                );
            }
            
            // 转换为业务对象
            ResumeAnalysisResponse result = convertToResponse(dto, resumeText);
            log.info("简历分析完成，总分: {}", result.overallScore());
            
            return result;
            
        } catch (BusinessException e) {
            log.error("简历分析失败: {}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("简历分析失败: {}", e.getMessage(), e);
            throw new BusinessException(
                ErrorCode.RESUME_ANALYSIS_FAILED,
                "简历分析失败：" + e.getMessage(),
                e
            );
        }
    }
    
    /**
     * 转换DTO为业务对象
     */
    private ResumeAnalysisResponse convertToResponse(ResumeAnalysisResponseDTO dto, String originalText) {
        ScoreDetail scoreDetail = new ScoreDetail(
            dto.scoreDetail().contentScore(),
            dto.scoreDetail().structureScore(),
            dto.scoreDetail().skillMatchScore(),
            dto.scoreDetail().expressionScore(),
            dto.scoreDetail().projectScore()
        );
        
        List<Suggestion> suggestions = dto.suggestions().stream()
            .map(s -> new Suggestion(s.category(), s.priority(), s.issue(), s.recommendation()))
            .toList();
        
        return new ResumeAnalysisResponse(
            dto.overallScore(),
            scoreDetail,
            dto.summary(),
            dto.strengths(),
            suggestions,
            originalText
        );
    }
    
}
