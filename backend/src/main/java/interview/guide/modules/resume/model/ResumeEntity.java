package interview.guide.modules.resume.model;

import interview.guide.common.model.AsyncTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 简历实体
 * Resume Entity for deduplication and persistence
 */
@Entity
@Table(name = "resumes", indexes = {
    @Index(name = "idx_resume_user_hash", columnList = "userId,fileHash", unique = true),
    @Index(name = "idx_resumes_user_id", columnList = "userId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResumeEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;
    
    @Column(nullable = false, length = 64)
    private String fileHash;
    
    @Column(nullable = false)
    private String originalFilename;
    
    private Long fileSize;
    
    private String contentType;
    
    @Column(length = 500)
    private String storageKey;
    
    @Column(length = 1000)
    private String storageUrl;
    
    @Column(columnDefinition = "TEXT")
    private String resumeText;
    
    @Column(nullable = false)
    private LocalDateTime uploadedAt;
    
    private LocalDateTime lastAccessedAt;
    
    @Builder.Default
    private Integer accessCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private AsyncTaskStatus analyzeStatus = AsyncTaskStatus.PENDING;

    @Column(length = 500)
    private String analyzeError;
    
    @PrePersist
    protected void onCreate() {
        uploadedAt = LocalDateTime.now();
        lastAccessedAt = LocalDateTime.now();
        if (accessCount == null) {
            accessCount = 1;
        }
    }
    
    public void incrementAccessCount() {
        this.accessCount++;
        this.lastAccessedAt = LocalDateTime.now();
    }
}
