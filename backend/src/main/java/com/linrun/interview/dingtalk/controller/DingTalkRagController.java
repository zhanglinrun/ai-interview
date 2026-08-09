package com.linrun.interview.dingtalk.controller;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.rag.model.QueryRequest;
import com.linrun.interview.rag.model.QueryResponse;
import com.linrun.interview.dingtalk.config.DingTalkProperties;
import com.linrun.interview.rag.service.KnowledgeBaseQueryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.util.List;

/**
 * 钉钉机器人入站适配器。
 *
 * <p>钉钉回调不具备平台会话，因此使用独立共享 Token，并显式携带平台用户 ID 与知识库范围；
 * 处理结果复用同步 RAG 查询，引用信息以结构化 JSON 返回。生产环境建议在网关再做签名/限流。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/rag/dingtalk")
@Tag(name = "RAG 钉钉入口", description = "钉钉机器人只读问答适配")
public class DingTalkRagController {

  private final KnowledgeBaseQueryService queryService;
  private final DingTalkProperties properties;

  @PostMapping("/query")
  public Result<QueryResponse> query(
      @RequestHeader(value = "X-RAG-DingTalk-Token", required = false) String token,
      @Valid @RequestBody DingTalkQueryRequest request) {
    if (!properties.isEnabled() || !StringUtils.hasText(properties.getVerificationToken())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "钉钉入口未启用");
    }
    if (!sameToken(properties.getVerificationToken(), token)) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED, "钉钉入口 Token 无效");
    }
    if (request.question().length() > Math.max(1, properties.getMaxQuestionChars())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "问题长度超过钉钉入口限制");
    }

    try {
      UserContext.setUserId(request.userId());
      return Result.success(queryService.queryKnowledgeBase(
          new QueryRequest(request.knowledgeBaseIds(), request.question())));
    } finally {
      UserContext.clear();
    }
  }

  private boolean sameToken(String expected, String actual) {
    if (!StringUtils.hasText(actual)) {
      return false;
    }
    return MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  public record DingTalkQueryRequest(
      @NotBlank(message = "问题不能为空") String question,
      @jakarta.validation.constraints.NotNull(message = "用户 ID 不能为空") Long userId,
      @NotEmpty(message = "至少选择一个知识库") List<Long> knowledgeBaseIds
  ) {
  }
}
