package com.linrun.interview.business.controller;

import com.linrun.interview.common.annotation.RateLimit;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.common.web.AttachmentResponseBuilder;
import com.linrun.interview.business.service.CandidateMemoryService.CandidateMemoryProfileDTO;
import com.linrun.interview.business.vo.InterviewMemoryView;
import com.linrun.interview.business.vo.AgentPlanProgressDTO;
import com.linrun.interview.business.vo.AgentTraceCatalogItemDTO;
import com.linrun.interview.business.vo.AgentTraceGroupDTO;
import com.linrun.interview.business.vo.AgentTracePlaybackDTO;
import com.linrun.interview.business.vo.AnswerBody;
import com.linrun.interview.business.vo.DraftAnswerBody;
import com.linrun.interview.business.vo.CreateInterviewRequest;
import com.linrun.interview.business.vo.InterviewDetailDTO;
import com.linrun.interview.business.vo.InterviewReportDTO;
import com.linrun.interview.business.vo.InterviewSessionDTO;
import com.linrun.interview.business.vo.SessionListItemDTO;
import com.linrun.interview.business.vo.SubmitAnswerRequest;
import com.linrun.interview.business.vo.SubmitAnswerResponse;
import com.linrun.interview.business.service.AgentTracePlaybackService;
import com.linrun.interview.business.service.InterviewHistoryService;
import com.linrun.interview.business.service.InterviewMemoryService;
import com.linrun.interview.business.service.InterviewPersistenceService;
import com.linrun.interview.business.service.InterviewSessionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 面试控制器
 * 提供模拟面试相关的API接口
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "模拟面试", description = "面试会话创建、问答交互与报告生成")
@RequestMapping("/api/v1/interviews")
public class InterviewController {
    
    private final InterviewSessionService sessionService;
    private final InterviewHistoryService historyService;
    private final InterviewPersistenceService persistenceService;
    private final AgentTracePlaybackService agentTracePlaybackService;
    private final InterviewMemoryService memoryService;
    
    /**
     * 列出所有面试会话（用于面试记录页）
     */
    @GetMapping("/sessions")
    public Result<List<SessionListItemDTO>> listSessions() {
        List<SessionListItemDTO> items = persistenceService.findAll().stream()
            .map(SessionListItemDTO::from)
            .toList();
        return Result.success(items);
    }

    /**
     * 创建面试会话
     */
    @PostMapping("/sessions")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<InterviewSessionDTO> createSession(@Valid @RequestBody CreateInterviewRequest request) {
        log.info("创建面试会话，题目数量: {}", request.questionCount());
        InterviewSessionDTO session = sessionService.createSession(request);
        return Result.success(session);
    }
    
    /**
     * 获取会话信息
     */
    @GetMapping("/sessions/{sessionId}")
    public Result<InterviewSessionDTO> getSession(@PathVariable String sessionId) {
        InterviewSessionDTO session = sessionService.getSession(sessionId);
        return Result.success(session);
    }
    
    /**
     * 获取当前问题
     */
    @GetMapping("/sessions/{sessionId}/question")
    public Result<Map<String, Object>> getCurrentQuestion(@PathVariable String sessionId) {
        return Result.success(sessionService.getCurrentQuestionResponse(sessionId));
    }
    
    /**
     * 提交答案
     */
    @PostMapping("/sessions/{sessionId}/answers")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<SubmitAnswerResponse> submitAnswer(
            @PathVariable String sessionId,
            @Valid @RequestBody AnswerBody body) {
        log.info("提交答案: 会话{}, 问题{}", sessionId, body.questionIndex());
        SubmitAnswerRequest request = new SubmitAnswerRequest(sessionId, body.commandId(),
            body.expectedSessionVersion(), body.questionIndex(), body.answer());
        SubmitAnswerResponse response = sessionService.submitAnswer(request);
        return Result.success(response);
    }
    
    /**
     * 生成面试报告
     */
    @GetMapping("/sessions/{sessionId}/report")
    public Result<InterviewReportDTO> getReport(@PathVariable String sessionId) {
        log.info("生成面试报告: {}", sessionId);
        InterviewReportDTO report = sessionService.generateReport(sessionId);
        return Result.success(report);
    }
    
    /**
     * 查找未完成的面试会话
     * GET /api/v1/interviews/sessions/unfinished/{resumeId}
     */
    @GetMapping("/sessions/unfinished/{resumeId}")
    public Result<InterviewSessionDTO> findUnfinishedSession(@PathVariable Long resumeId) {
        // “没有未完成会话”是简历详情页的正常空状态，不应转成业务异常污染日志。
        return Result.success(sessionService.findUnfinishedSession(resumeId).orElse(null));
    }
    
    /**
     * 暂存答案（不进入下一题）
     */
    @PutMapping("/sessions/{sessionId}/answers")
    public Result<Void> saveAnswer(
            @PathVariable String sessionId,
            @Valid @RequestBody DraftAnswerBody body) {
        log.info("暂存答案: 会话{}, 问题{}", sessionId, body.questionIndex());
        SubmitAnswerRequest request = new SubmitAnswerRequest(sessionId, body.questionIndex(), body.answer());
        sessionService.saveAnswer(request);
        return Result.success(null);
    }
    
    /**
     * 提前交卷
     */
    @PostMapping("/sessions/{sessionId}/complete")
    public Result<Void> completeInterview(@PathVariable String sessionId) {
        log.info("提前交卷: {}", sessionId);
        sessionService.completeInterview(sessionId);
        return Result.success(null);
    }

    @PostMapping("/sessions/{sessionId}/reevaluate")
    public Result<Void> reevaluateInterview(@PathVariable String sessionId) {
        log.info("重新评估面试: {}", sessionId);
        sessionService.reevaluate(sessionId);
        return Result.success(null);
    }
    
    /**
     * 获取面试会话详情
     * GET /api/v1/interviews/sessions/{sessionId}/details
     */
    @GetMapping("/sessions/{sessionId}/details")
    public Result<InterviewDetailDTO> getInterviewDetail(@PathVariable String sessionId) {
        InterviewDetailDTO detail = historyService.getInterviewDetail(sessionId);
        return Result.success(detail);
    }

    /**
     * Agent Trace 目录：带步骤数，回放页优先选有轨迹的会话。
     */
    @GetMapping("/agent-traces")
    public Result<List<AgentTraceCatalogItemDTO>> listAgentTraces() {
        return Result.success(agentTracePlaybackService.listCatalog());
    }

    /**
     * 获取会话的 Multi-Agent 决策轨迹（Planner→Interviewer→Critic→Reflexion，按题号分组回放）
     * GET /api/v1/interviews/sessions/{sessionId}/agent-trace
     */
    @GetMapping("/sessions/{sessionId}/agent-trace")
    public Result<List<AgentTraceGroupDTO>> getAgentTrace(@PathVariable String sessionId) {
        return Result.success(sessionService.getAgentTrace(sessionId));
    }

    /**
     * 结构化回放：解释 TurnDecision / Critic / Grounding / Reflexion。
     */
    @GetMapping("/sessions/{sessionId}/agent-trace/playback")
    public Result<AgentTracePlaybackDTO> getAgentTracePlayback(@PathVariable String sessionId) {
        return Result.success(agentTracePlaybackService.getPlayback(sessionId));
    }

    /**
     * 获取会话的面试大纲与进度（前端侧栏大纲进度条）
     * GET /api/v1/interviews/sessions/{sessionId}/agent-plan
     */
    @GetMapping("/sessions/{sessionId}/agent-plan")
    public Result<AgentPlanProgressDTO> getAgentPlan(@PathVariable String sessionId) {
        return Result.success(sessionService.getAgentPlan(sessionId));
    }

    /**
     * 三层记忆：短期原文、本场压缩摘要、跨场长期观测。skillId 可选。
     * GET /api/v1/interviews/memory
     */
    @GetMapping("/memory")
    public Result<InterviewMemoryView> getMemory(
            @RequestParam(required = false) String skillId) {
        return Result.success(memoryService.getMemory(skillId));
    }

    /**
     * 仅长期记忆列表。新页面走 /memory；保留此接口避免旧客户端中断。
     * GET /api/v1/interviews/candidate-memory/profile
     */
    @GetMapping("/candidate-memory/profile")
    public Result<List<CandidateMemoryProfileDTO>> getCandidateProfile(
            @RequestParam(required = false) String skillId) {
        return Result.success(sessionService.getCandidateProfile(skillId));
    }
    
    /**
     * 导出面试报告为PDF
     */
    @GetMapping("/sessions/{sessionId}/export")
    public ResponseEntity<byte[]> exportInterviewPdf(@PathVariable String sessionId) {
        try {
            byte[] pdfBytes = historyService.exportInterviewPdf(sessionId);
            return AttachmentResponseBuilder.pdf("模拟面试报告_" + sessionId + ".pdf", pdfBytes);
        } catch (Exception e) {
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            log.error("导出PDF失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.EXPORT_PDF_FAILED, "导出面试报告失败", e);
        }
    }
    
    /**
     * 删除面试会话
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteInterview(@PathVariable String sessionId) {
        log.info("删除面试会话: {}", sessionId);
        sessionService.deleteSession(sessionId);
        return Result.success(null);
    }
}
