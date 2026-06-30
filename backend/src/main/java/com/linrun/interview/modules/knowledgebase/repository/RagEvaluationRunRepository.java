package com.linrun.interview.modules.knowledgebase.repository;

import com.linrun.interview.modules.knowledgebase.model.RagEvaluationRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RagEvaluationRunRepository extends JpaRepository<RagEvaluationRunEntity, Long> {
}
