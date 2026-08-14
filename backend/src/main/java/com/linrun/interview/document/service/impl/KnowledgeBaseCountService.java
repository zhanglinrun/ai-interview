package com.linrun.interview.document.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.infra.persistence.EntityQueries;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseCountService {

  private final KnowledgeBaseEntityMapper knowledgeBaseEntityMapper;

  @Transactional(rollbackFor = Exception.class)
  public void updateQuestionCounts(List<Long> knowledgeBaseIds) {
    if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()) {
      return;
    }

    List<Long> uniqueIds = knowledgeBaseIds.stream().distinct().toList();
    Long userId = UserContext.requireUserId();

    Set<Long> existingIds = new HashSet<>(EntityQueries.listByUserIdAndIdIn(
        knowledgeBaseEntityMapper, userId, uniqueIds,
        KnowledgeBaseEntity::getUserId, KnowledgeBaseEntity::getId)
      .stream().map(KnowledgeBaseEntity::getId).toList());

    for (Long id : uniqueIds) {
      if (!existingIds.contains(id)) {
        throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + id);
      }
    }

    int updated = knowledgeBaseEntityMapper.incrementQuestionCountBatch(userId, uniqueIds);
    log.debug("批量更新知识库提问计数: ids={}, updated={}", uniqueIds, updated);
  }
}
