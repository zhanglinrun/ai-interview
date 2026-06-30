package com.linrun.interview.modules.knowledgebase.repository;

import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseDataTableEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgeBaseDataTableRepository extends JpaRepository<KnowledgeBaseDataTableEntity, Long> {

    Optional<KnowledgeBaseDataTableEntity> findByUserIdAndDocId(Long userId, Long docId);

    List<KnowledgeBaseDataTableEntity> findAllByUserId(Long userId);

    void deleteByUserIdAndDocId(Long userId, Long docId);
}
