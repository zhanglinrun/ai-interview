package com.linrun.interview.modules.resume.repository;

import com.linrun.interview.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 简历Repository
 */
@Repository
public interface ResumeRepository extends JpaRepository<ResumeEntity, Long> {
    
    /**
     * 根据文件哈希查找简历（用于去重）
     */
    Optional<ResumeEntity> findByFileHash(String fileHash);

    Optional<ResumeEntity> findByUserIdAndFileHash(Long userId, String fileHash);

    Optional<ResumeEntity> findByUserIdAndId(Long userId, Long id);

    List<ResumeEntity> findAllByUserIdOrderByUploadedAtDesc(Long userId);
    
    /**
     * 检查文件哈希是否存在
     */
    boolean existsByFileHash(String fileHash);

    boolean existsByUserIdAndId(Long userId, Long id);
}
