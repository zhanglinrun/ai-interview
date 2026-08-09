package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.document.service.FileHashService;
import com.linrun.interview.business.converter.ResumeMapper;
import com.linrun.interview.infra.redis.RedisService;
import com.linrun.interview.business.vo.ResumeAnalysisResponse;
import com.linrun.interview.business.mapper.ResumeAnalysisMapper;
import com.linrun.interview.business.mapper.ResumeEntityMapper;
import com.linrun.interview.business.entity.ResumeAnalysisEntity;
import com.linrun.interview.business.entity.ResumeEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 简历持久化服务
 * 简历和评测结果的持久化，简历删除时删除所有关联数据
 */
@Slf4j
@Service
public class ResumePersistenceService extends ServiceImpl<ResumeEntityMapper, ResumeEntity> {

  private static final Duration NULL_ID_TTL = Duration.ofMinutes(2);
  private static final String NULL_ID_PREFIX = "resume:null:";
  private static final int PROJECT_SCORE_MAX = 40;
  private static final int SKILL_MATCH_SCORE_MAX = 20;
  private static final int CONTENT_SCORE_MAX = 15;
  private static final int STRUCTURE_SCORE_MAX = 15;
  private static final int EXPRESSION_SCORE_MAX = 10;

  private final ResumeAnalysisMapper resumeAnalysisMapper;
  private final ObjectMapper objectMapper;
  private final ResumeMapper resumeMapper;
  private final FileHashService fileHashService;
  private final RedisService redisService;

  public ResumePersistenceService(
      ResumeEntityMapper resumeEntityMapper,
      ResumeAnalysisMapper resumeAnalysisMapper,
      ObjectMapper objectMapper,
      ResumeMapper resumeMapper,
      FileHashService fileHashService,
      RedisService redisService) {
    this.baseMapper = resumeEntityMapper;
    this.resumeAnalysisMapper = resumeAnalysisMapper;
    this.objectMapper = objectMapper;
    this.resumeMapper = resumeMapper;
    this.fileHashService = fileHashService;
    this.redisService = redisService;
  }

  public Optional<ResumeEntity> findExistingResume(MultipartFile file) {
    Long userId = UserContext.requireUserId();
    try {
      String fileHash = fileHashService.calculateHash(file);
      Optional<ResumeEntity> existing = Optional.ofNullable(baseMapper.selectOne(
        Wrappers.<ResumeEntity>lambdaQuery()
          .eq(ResumeEntity::getUserId, userId)
          .eq(ResumeEntity::getFileHash, fileHash)));

      if (existing.isPresent()) {
        log.info("检测到重复简历: hash={}", fileHash);
        ResumeEntity resume = existing.get();
        resume.incrementAccessCount();
        updateById(resume);
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

      save(resume);
      ResumeEntity saved = resume;
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
      ResumeAnalysisResponse normalizedAnalysis = normalizeScores(analysis);
      if (analysis.overallScore() != normalizedAnalysis.overallScore()
          || !normalizedAnalysis.scoreDetail().equals(analysis.scoreDetail())) {
        log.warn(
            "简历评分越界或总分不一致，写库前已归一化: resumeId={}, rawOverall={}, normalizedOverall={}, "
                + "rawDetail={}, normalizedDetail={}",
            resume.getId(), analysis.overallScore(), normalizedAnalysis.overallScore(),
            analysis.scoreDetail(), normalizedAnalysis.scoreDetail());
      }

      ResumeAnalysisEntity entity = resumeMapper.toAnalysisEntity(normalizedAnalysis);
      entity.setUserId(resume.getUserId());
      entity.setResumeId(resume.getId());
      entity.setAnalyzedAt(LocalDateTime.now());
      entity.setStrengthsJson(objectMapper.writeValueAsString(normalizedAnalysis.strengths()));
      entity.setSuggestionsJson(objectMapper.writeValueAsString(normalizedAnalysis.suggestions()));

      resumeAnalysisMapper.insert(entity);
      ResumeAnalysisEntity saved = entity;
      log.info("简历评测结果已保存: analysisId={}, resumeId={}, score={}",
          saved.getId(), resume.getId(), normalizedAnalysis.overallScore());
      return saved;
    } catch (JsonProcessingException e) {
      log.error("序列化评测结果失败: {}", e.getMessage(), e);
      throw new BusinessException(ErrorCode.RESUME_ANALYSIS_FAILED, "保存评测结果失败", e);
    }
  }

  private ResumeAnalysisResponse normalizeScores(ResumeAnalysisResponse analysis) {
    ResumeAnalysisResponse.ScoreDetail raw = analysis.scoreDetail();
    ResumeAnalysisResponse.ScoreDetail normalized = raw == null
        ? new ResumeAnalysisResponse.ScoreDetail(0, 0, 0, 0, 0)
        : new ResumeAnalysisResponse.ScoreDetail(
            clampScore(raw.contentScore(), CONTENT_SCORE_MAX),
            clampScore(raw.structureScore(), STRUCTURE_SCORE_MAX),
            clampScore(raw.skillMatchScore(), SKILL_MATCH_SCORE_MAX),
            clampScore(raw.expressionScore(), EXPRESSION_SCORE_MAX),
            clampScore(raw.projectScore(), PROJECT_SCORE_MAX));
    int normalizedOverall = normalized.contentScore()
        + normalized.structureScore()
        + normalized.skillMatchScore()
        + normalized.expressionScore()
        + normalized.projectScore();
    return new ResumeAnalysisResponse(
        normalizedOverall,
        normalized,
        analysis.summary(),
        analysis.strengths(),
        analysis.suggestions(),
        analysis.originalText());
  }

  private int clampScore(int score, int maximum) {
    return Math.max(0, Math.min(score, maximum));
  }

  public Optional<ResumeAnalysisEntity> getLatestAnalysis(Long resumeId) {
    Long userId = UserContext.requireUserId();
    return Optional.ofNullable(resumeAnalysisMapper.selectOne(
      Wrappers.<ResumeAnalysisEntity>lambdaQuery()
        .eq(ResumeAnalysisEntity::getUserId, userId)
        .eq(ResumeAnalysisEntity::getResumeId, resumeId)
        .orderByDesc(ResumeAnalysisEntity::getAnalyzedAt)
        .last("LIMIT 1")));
  }

  public Optional<ResumeAnalysisResponse> getLatestAnalysisAsDTO(Long resumeId) {
    return getLatestAnalysis(resumeId).map(this::entityToDTO);
  }

  public List<ResumeEntity> findAllResumes() {
    return list(Wrappers.<ResumeEntity>lambdaQuery()
      .eq(ResumeEntity::getUserId, UserContext.requireUserId())
      .orderByDesc(ResumeEntity::getUploadedAt));
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
        ResumeEntity resume = getById(entity.getResumeId());
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
    if (id == null) {
      return Optional.empty();
    }
    Long userId = UserContext.requireUserId();
    String nullKey = NULL_ID_PREFIX + userId + ":" + id;
    if (Boolean.TRUE.equals(redisService.get(nullKey))) {
      return Optional.empty();
    }
    Optional<ResumeEntity> result = Optional.ofNullable(baseMapper.selectOne(
      Wrappers.<ResumeEntity>lambdaQuery()
        .eq(ResumeEntity::getUserId, userId)
        .eq(ResumeEntity::getId, id)));
    if (result.isEmpty()) {
      redisService.set(nullKey, true, NULL_ID_TTL);
    }
    return result;
  }

  @Transactional(rollbackFor = Exception.class)
  public void deleteResume(Long id) {
    Long userId = UserContext.requireUserId();
    ResumeEntity resume = getOne(Wrappers.<ResumeEntity>lambdaQuery()
        .eq(ResumeEntity::getUserId, userId)
        .eq(ResumeEntity::getId, id));
    if (resume == null) {
      throw new BusinessException(ErrorCode.RESUME_NOT_FOUND);
    }

    resumeAnalysisMapper.delete(Wrappers.<ResumeAnalysisEntity>lambdaQuery()
      .eq(ResumeAnalysisEntity::getUserId, userId)
      .eq(ResumeAnalysisEntity::getResumeId, id));

    removeById(resume.getId());
    log.info("简历已删除: id={}, filename={}", id, resume.getOriginalFilename());
  }
}
