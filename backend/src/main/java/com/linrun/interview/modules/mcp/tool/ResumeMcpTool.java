package com.linrun.interview.modules.mcp.tool;

import com.linrun.interview.modules.interview.model.ResumeAnalysisResponse;
import com.linrun.interview.modules.mcp.config.McpServerProperties;
import com.linrun.interview.modules.mcp.support.McpUserContexts;
import com.linrun.interview.modules.resume.model.ResumeEntity;
import com.linrun.interview.modules.resume.service.ResumePersistenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Optional;

/**
 * MCP 工具：读取候选人简历原文与最新 AI 评分摘要。
 * 归属校验由 {@link ResumePersistenceService#findById}（userId + id 联合查询）完成。
 */
@Slf4j
@RequiredArgsConstructor
public class ResumeMcpTool {

    private static final int MAX_RESUME_CHARS = 6000;

    private final ResumePersistenceService resumePersistenceService;
    private final McpServerProperties mcpProperties;

    public record ResumeMcpResult(
        Long resumeId,
        String filename,
        String uploadedAt,
        Integer latestScore,
        String analysisSummary,
        String resumeText,
        String message
    ) {
        static ResumeMcpResult error(Long resumeId, String message) {
            return new ResumeMcpResult(resumeId, null, null, null, null, null, message);
        }
    }

    @Tool(name = "read_resume", description = "读取候选人简历：返回简历原文、上传信息与最新 AI 评分摘要。"
        + "resumeId 可从 list_history 工具的结果或平台简历页获取。")
    public ResumeMcpResult readResume(@ToolParam(description = "简历ID") Long resumeId) {
        if (resumeId == null) {
            return ResumeMcpResult.error(null, "resumeId 不能为空");
        }
        return McpUserContexts.runAs(mcpProperties.getUserId(), () -> doRead(resumeId));
    }

    private ResumeMcpResult doRead(Long resumeId) {
        try {
            Optional<ResumeEntity> resumeOpt = resumePersistenceService.findById(resumeId);
            if (resumeOpt.isEmpty()) {
                return ResumeMcpResult.error(resumeId, "简历不存在或不属于当前 MCP 用户");
            }
            ResumeEntity resume = resumeOpt.get();
            Optional<ResumeAnalysisResponse> analysis =
                resumePersistenceService.getLatestAnalysisAsDTO(resumeId);
            return new ResumeMcpResult(
                resumeId,
                resume.getOriginalFilename(),
                resume.getUploadedAt() == null ? null : resume.getUploadedAt().toString(),
                analysis.map(ResumeAnalysisResponse::overallScore).orElse(null),
                analysis.map(ResumeAnalysisResponse::summary).orElse(null),
                truncate(resume.getResumeText()),
                null);
        } catch (Exception e) {
            log.warn("[McpTool] read_resume 失败: resumeId={}", resumeId, e);
            return ResumeMcpResult.error(resumeId, "读取简历失败: " + e.getMessage());
        }
    }

    private String truncate(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= MAX_RESUME_CHARS
            ? trimmed
            : trimmed.substring(0, MAX_RESUME_CHARS) + "...(简历较长，已截断)";
    }
}
