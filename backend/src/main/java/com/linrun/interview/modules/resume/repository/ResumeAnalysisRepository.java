package com.linrun.interview.modules.resume.repository;

import com.linrun.interview.modules.resume.model.ResumeAnalysisEntity;
import com.linrun.interview.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 简历评测Repository
 */
@Repository
public interface ResumeAnalysisRepository extends JpaRepository<ResumeAnalysisEntity, Long> {
    
    /**
     * 根据简历查找所有评测记录
     */
    List<ResumeAnalysisEntity> findByResumeOrderByAnalyzedAtDesc(ResumeEntity resume);
    
    /**
     * 根据简历ID查找最新评测记录
     */
    ResumeAnalysisEntity findFirstByResumeIdOrderByAnalyzedAtDesc(Long resumeId);

    ResumeAnalysisEntity findFirstByUserIdAndResumeIdOrderByAnalyzedAtDesc(Long userId, Long resumeId);
    
    /**
     * 根据简历ID查找所有评测记录
     */
    List<ResumeAnalysisEntity> findByResumeIdOrderByAnalyzedAtDesc(Long resumeId);

    List<ResumeAnalysisEntity> findByUserIdAndResumeIdOrderByAnalyzedAtDesc(Long userId, Long resumeId);
}
