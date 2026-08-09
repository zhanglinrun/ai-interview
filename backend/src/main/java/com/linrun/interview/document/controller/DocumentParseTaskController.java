package com.linrun.interview.document.controller;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.document.vo.DocumentParseTaskDTO;
import com.linrun.interview.document.service.DocumentParseTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DocumentParseTaskController {

  private final DocumentParseTaskService taskService;

  @GetMapping("/api/v1/knowledge-bases/{documentId}/versions/{versionId}/parse-task")
  public Result<DocumentParseTaskDTO> latest(
      @PathVariable Long documentId,
      @PathVariable Long versionId
  ) {
    Long userId = UserContext.requireUserId();
    DocumentParseTaskDTO task = taskService.findLatest(userId, documentId, versionId)
        .map(DocumentParseTaskDTO::from)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.NOT_FOUND, "该版本没有解析任务"));
    return Result.success(task);
  }
}
