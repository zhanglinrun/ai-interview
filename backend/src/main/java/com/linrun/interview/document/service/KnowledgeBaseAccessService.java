package com.linrun.interview.document.service;

import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;
import com.linrun.interview.document.entity.KnowledgeBaseEntity;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 文档所有权边界，避免 Controller 直接拼接 Mapper 查询。 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseAccessService {
    private final KnowledgeBaseEntityMapper mapper;

    public KnowledgeBaseEntity requireOwner(Long documentId) {
        Long userId = UserContext.requireUserId();
        KnowledgeBaseEntity entity = mapper.selectOne(Wrappers.<KnowledgeBaseEntity>lambdaQuery()
            .eq(KnowledgeBaseEntity::getId, documentId)
            .eq(KnowledgeBaseEntity::getUserId, userId));
        if (entity == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在或无权访问");
        }
        return entity;
    }
}
