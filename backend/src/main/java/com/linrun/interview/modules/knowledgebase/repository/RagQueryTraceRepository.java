package com.linrun.interview.modules.knowledgebase.repository;

import com.linrun.interview.modules.knowledgebase.model.RagQueryTraceEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RagQueryTraceRepository extends JpaRepository<RagQueryTraceEntity, Long> {

    List<RagQueryTraceEntity> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Optional<RagQueryTraceEntity> findByUserIdAndTraceId(Long userId, String traceId);
}
