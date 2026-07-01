package com.linrun.interview.modules.resume.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.common.mybatis.EntityQueries;
import com.linrun.interview.common.mybatis.MapperUtils;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.infrastructure.file.FileHashService;
import com.linrun.interview.infrastructure.mapper.ResumeMapper;
import com.linrun.interview.modules.interview.model.ResumeAnalysisResponse;
import com.linrun.interview.modules.resume.mapper.ResumeAnalysisMapper;
import com.linrun.interview.modules.resume.mapper.ResumeEntityMapper;
import com.linrun.interview.modules.resume.model.ResumeAnalysisEntity;
import com.linrun.interview.modules.resume.model.ResumeEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 简历持久化服务
 * 简历和评测结果的持久化，简历删除时删除所有关联数据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResumePersistenceService {

  private final ResumeEntityMapper resumeEntityMapper;
  private final ResumeAnalysisMapper resumeAnalysisMapper;
  private final ObjectMapper objectMapper;
  private final ResumeMapper resumeMapper;
  private final FileHashService fileHashService;

  public Optional<ResumeEntity> findExistingResume(MultipartFile file) {
    Long userId = UserContext.requireUserId();
    try {
      String fileHash = fileHashService.calculateHash(file);
      Optional<ResumeEntity> existing = MapperUtils.selectOneOptional(resumeEntityMapper,
        Wrappers.<ResumeEntity>lambdaQuery()
          .eq(ResumeEntity::getUserId, userId)
          .eq(ResumeEntity::getFileHash, fileHash));

      if (existing.isPresent()) {
        log.info("检测到重复简历: hash={}", fileHash);
        ResumeEntity resume = existing.get();
        resume.incrementAccessCount();
        MapperUtils.save(resumeEntityMapper, resume);
      }
      return existing;
    } catch (Exception e) {
      log.error("检查简历重复时出错: {}", e.getMessage(), e);
      return Optional.empty();
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public ResumeEntity saveResume(MultipartFile file, String resumeText,
                                 String storageKey, String storageUrl) {
    Long userId = UserContext.requireUserId();
    try {
      String fileHash = fileHashService.calculateHash(file);

      ResumeEntity resume = ResumeEntity.builder()
        .userId(userId)
        .fileHash(fileHash)
        .originalFilename(file.getOriginalFilename())
        .fileSize(file.getSize())
        .contentType(file.getContentType())
        .storageKey(storageKey)
        .storageUrl(storageUrl)
        .resumeText(resumeText)
        .uploadedAt(LocalDateTime.now())
        .lastAccessedAt(LocalDateTime.now())
        .accessCount(1)
        .build();

      ResumeEntity saved = MapperUtils.save(resumeEntityMapper, resume);
      log.info("简历已保存: id={}, hash={}", saved.getId(), fileHash);
      return saved;
    } catch (Exception e) {
      log.error("保存简历失败: {}", e.getMessage(), e);
      throw new BusinessException(ErrorCode.RESUME_UPLOAD_FAILED, "保存简历失败", e);
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public ResumeAnalysisEntity saveAnalysis(ResumeEntity resume, ResumeAnalysisResponse analysis) {
    try {
      ResumeAnalysisEntity entity = resumeMapper.toAnalysisEntity(analysis);
      entity.setUserId(resume.getUserId());
      entity.setResumeId(resume.getId());
      entity.setAnalyzedAt(LocalDateTime.now());
      entity.setStrengthsJson(objectMapper.writeValueAsString(analysis.strengths()));
      entity.setSuggestionsJson(objectMapper.writeValueAsString(analysis.suggestions()));

      ResumeAnalysisEntity saved = MapperUtils.save(resumeAnalysisMapper, entity);
      log.info("简历评测结果已保存: analysisId={}, resumeId={}, score={}",
          saved.getId(), resume.getId(), analysis.overallScore());
      return saved;
    } catch (JsonProcessingException e) {
      log.error("序列化评测结果失败: {}", e.getMessage(), e);
      throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "保存评测结果失败", e);
    }
  }

  public Optional<ResumeAnalysisEntity> getLatestAnalysis(Long resumeId) {
    Long userId = UserContext.requireUserId();
    return MapperUtils.selectOneOptional(resumeAnalysisMapper,
      Wrappers.<ResumeAnalysisEntity>lambdaQuery()
        .eq(ResumeAnalysisEntity::getUserId, userId)
        .eq(ResumeAnalysisEntity::getResumeId, resumeId)
        .orderByDesc(ResumeAnalysisEntity::getAnalyzedAt)
        .last("LIMIT 1"));
  }

  public Optional<ResumeAnalysisResponse> getLatestAnalysisAsDTO(Long resumeId) {
    return getLatestAnalysis(resumeId).map(this::entityToDTO);
  }

  public List<ResumeEntity> findAllResumes() {
    return EntityQueries.listByUserIdOrderByDesc(
      resumeEntityMapper,
      UserContext.requireUserId(),
      ResumeEntity::getUserId,
      ResumeEntity::getUploadedAt);
  }

  public List<ResumeAnalysisEntity> findAnalysesByResumeId(Long resumeId) {
    Long userId = UserContext.requireUserId();
    return resumeAnalysisMapper.selectList(
      Wrappers.<ResumeAnalysisEntity>lambdaQuery()
        .eq(ResumeAnalysisEntity::getUserId, userId)
        .eq(ResumeAnalysisEntity::getResumeId, resumeId)
        .orderByDesc(ResumeAnalysisEntity::getAnalyzedAt));
  }

  public ResumeAnalysisResponse entityToDTO(ResumeAnalysisEntity entity) {
    try {
      List<String> strengths = objectMapper.readValue(
        entity.getStrengthsJson() != null ? entity.getStrengthsJson() : "[]",
        new TypeReference<>() {}
      );

      List<ResumeAnalysisResponse.Suggestion> suggestions = objectMapper.readValue(
        entity.getSuggestionsJson() != null ? entity.getSuggestionsJson() : "[]",
        new TypeReference<>() {}
      );

      String resumeText = "";
      if (entity.getResumeId() != null) {
        ResumeEntity resume = resumeEntityMapper.selectById(entity.getResumeId());
        if (resume != null && resume.getResumeText() != null) {
          resumeText = resume.getResumeText();
        }
      }

      return new ResumeAnalysisResponse(
        entity.getOverallScore(),
        resumeMapper.toScoreDetail(entity),
        entity.getSummary(),
        strengths,
        suggestions,
        resumeText
      );
    } catch (JsonProcessingException e) {
      log.error("反序列化评测结果失败: {}", e.getMessage(), e);
      throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "获取评测结果失败", e);
    }
  }

  public Optional<ResumeEntity> findById(Long id) {
    return EntityQueries.byUserAndId(
      resumeEntityMapper, UserContext.requireUserId(), id,
      ResumeEntity::getUserId, ResumeEntity::getId);
  }

  @Transactional(rollbackFor = Exception.class)
  public void deleteResume(Long id) {
    Long userId = UserContext.requireUserId();
    ResumeEntity resume = EntityQueries.byUserAndId(
        resumeEntityMapper, userId, id, ResumeEntity::getUserId, ResumeEntity::getId)
      .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));

    resumeAnalysisMapper.delete(Wrappers.<ResumeAnalysisEntity>lambdaQuery()
      .eq(ResumeAnalysisEntity::getUserId, userId)
      .eq(ResumeAnalysisEntity::getResumeId, id));

    resumeEntityMapper.deleteById(resume.getId());
    log.info("简历已删除: id={}, filename={}", id, resume.getOriginalFilename());
  }
}
