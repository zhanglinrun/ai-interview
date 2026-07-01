package com.linrun.interview.modules.knowledgebase;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.mybatis.EntityQueries;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseEntity;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseSegmentDTO;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseSegmentPageDTO;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeSegmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库分段查询 API（对齐 know-engine KnowledgeSegmentController，只读 + DTO）。
 */
@RestController
@RequestMapping("/api/knowledgebase/segment")
@RequiredArgsConstructor
public class KnowledgeSegmentController {

  private final KnowledgeSegmentService segmentService;
  private final KnowledgeBaseEntityMapper knowledgeBaseEntityMapper;

  @GetMapping("/{id}")
  public Result<KnowledgeBaseSegmentDTO> getById(@PathVariable Long id) {
    KnowledgeBaseSegmentEntity segment = segmentService.findById(id);
    if (segment == null) {
      return Result.error("分段不存在");
    }
    requireDocumentAccess(segment.getDocumentId());
    return Result.success(KnowledgeBaseSegmentDTO.from(segment));
  }

  @GetMapping("/list-by-document")
  public Result<java.util.List<KnowledgeBaseSegmentDTO>> listByDocument(
      @RequestParam Long documentId,
      @RequestParam(required = false) Long documentVersion) {
    requireDocumentAccess(documentId);
    var segments = documentVersion != null
        ? segmentService.findByVersionId(documentVersion)
        : segmentService.findByDocumentId(documentId);
    return Result.success(segments.stream().map(KnowledgeBaseSegmentDTO::from).toList());
  }

  @GetMapping("/page-by-document")
  public Result<KnowledgeBaseSegmentPageDTO> pageByDocument(
      @RequestParam Long documentId,
      @RequestParam(required = false) Long documentVersion,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    requireDocumentAccess(documentId);
    var result = segmentService.pageByDocument(documentId, documentVersion, page, size);
    return Result.success(new KnowledgeBaseSegmentPageDTO(
        result.getTotal(),
        result.getCurrent(),
        result.getSize(),
        result.getRecords().stream().map(KnowledgeBaseSegmentDTO::from).toList()));
  }

  @GetMapping("/count-by-document")
  public Result<Long> countByDocument(
      @RequestParam Long documentId,
      @RequestParam(required = false) Long documentVersion) {
    requireDocumentAccess(documentId);
    return Result.success(segmentService.countByDocument(documentId, documentVersion));
  }

  private void requireDocumentAccess(Long docId) {
    EntityQueries.byUserAndId(
        knowledgeBaseEntityMapper,
        UserContext.requireUserId(),
        docId,
        KnowledgeBaseEntity::getUserId,
        KnowledgeBaseEntity::getId)
      .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
  }
}
