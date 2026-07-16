package com.linrun.interview.modules.algorithm.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.algorithm.dto.CodingProblemDetailDTO;
import com.linrun.interview.modules.algorithm.dto.CodingProblemDetailDTO.ComplexityRubricDTO;
import com.linrun.interview.modules.algorithm.dto.CodingProblemSummaryDTO;
import com.linrun.interview.modules.algorithm.dto.LanguageTemplateDTO;
import com.linrun.interview.modules.algorithm.dto.PublicExampleDTO;
import com.linrun.interview.modules.algorithm.mapper.CodingProblemMapper;
import com.linrun.interview.modules.algorithm.mapper.CodingProblemVersionMapper;
import com.linrun.interview.modules.algorithm.model.AlgorithmCatalogContent.ComplexityRubricDefinition;
import com.linrun.interview.modules.algorithm.model.AlgorithmCatalogContent.LanguageSpecDefinition;
import com.linrun.interview.modules.algorithm.model.AlgorithmCatalogContent.PublicExampleDefinition;
import com.linrun.interview.modules.algorithm.model.CodingLanguage;
import com.linrun.interview.modules.algorithm.model.CodingProblemEntity;
import com.linrun.interview.modules.algorithm.model.CodingProblemVersionEntity;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AlgorithmCatalogService {

  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
  };
  private static final TypeReference<List<PublicExampleDefinition>> EXAMPLE_LIST =
      new TypeReference<>() {
      };
  private static final TypeReference<List<LanguageSpecDefinition>> LANGUAGE_LIST =
      new TypeReference<>() {
      };

  private final CodingProblemMapper problemMapper;
  private final CodingProblemVersionMapper versionMapper;
  private final ObjectMapper objectMapper;

  public List<CodingProblemSummaryDTO> listEnabled(CodingLanguage language, String tag) {
    List<CodingProblemEntity> problems = problemMapper.selectList(
        Wrappers.<CodingProblemEntity>lambdaQuery()
            .eq(CodingProblemEntity::getActive, true)
            .orderByAsc(CodingProblemEntity::getHotRank));
    if (problems.isEmpty()) {
      return List.of();
    }
    List<Long> ids = problems.stream().map(CodingProblemEntity::getId).toList();
    Map<Long, CodingProblemVersionEntity> versions = versionMapper.selectList(
            Wrappers.<CodingProblemVersionEntity>lambdaQuery()
                .in(CodingProblemVersionEntity::getProblemId, ids)
                .eq(CodingProblemVersionEntity::getEnabled, true)
                .orderByDesc(CodingProblemVersionEntity::getCreatedAt))
        .stream()
        .collect(Collectors.toMap(CodingProblemVersionEntity::getProblemId,
            Function.identity(), (first, ignored) -> first));
    List<CodingProblemSummaryDTO> result = new ArrayList<>();
    for (CodingProblemEntity problem : problems) {
      CodingProblemVersionEntity version = versions.get(problem.getId());
      if (version == null || !supports(version, language)) {
        continue;
      }
      List<String> tags = read(problem.getTagsJson(), STRING_LIST, "题目标签配置损坏");
      if (tag != null && !tag.isBlank()
          && tags.stream().noneMatch(value -> value.equalsIgnoreCase(tag.trim()))) {
        continue;
      }
      result.add(toSummary(problem, version, tags));
    }
    return List.copyOf(result);
  }

  public CodingProblemDetailDTO getDetail(Long problemVersionId) {
    CodingProblemVersionEntity version = requireVersion(problemVersionId);
    CodingProblemEntity problem = problemMapper.selectById(version.getProblemId());
    if (problem == null || !Boolean.TRUE.equals(problem.getActive())) {
      throw new BusinessException(ErrorCode.CODING_PROBLEM_NOT_FOUND);
    }
    List<String> tags = read(problem.getTagsJson(), STRING_LIST, "题目标签配置损坏");
    List<PublicExampleDTO> examples = read(
        version.getPublicExamplesJson(), EXAMPLE_LIST, "公开示例配置损坏").stream()
        .map(item -> new PublicExampleDTO(item.input(), item.output(), item.explanation()))
        .toList();
    ComplexityRubricDefinition rubric = read(
        version.getComplexityRubricJson(), ComplexityRubricDefinition.class,
        "复杂度 Rubric 配置损坏");
    List<LanguageTemplateDTO> languages = read(
        version.getLanguageSpecsJson(), LANGUAGE_LIST, "语言模板配置损坏").stream()
        .filter(item -> Boolean.TRUE.equals(item.enabled()))
        .map(item -> new LanguageTemplateDTO(
            item.language(), item.functionSignature(), item.template()))
        .toList();
    return new CodingProblemDetailDTO(
        problem.getId(), version.getId(), problem.getHotRank(), problem.getPlatformProblemId(),
        problem.getTitle(), problem.getDifficulty(), tags, problem.getSourceUrl(),
        version.getVersion(), version.getStatementText(),
        read(version.getConstraintsJson(), STRING_LIST, "题目约束配置损坏"),
        examples, new ComplexityRubricDTO(rubric.expectedTime(), rubric.expectedSpace(),
        rubric.discussionPoints()), languages);
  }

  public CodingProblemVersionEntity requireEnabledVersion(
      Long problemVersionId,
      CodingLanguage language
  ) {
    CodingProblemVersionEntity version = requireVersion(problemVersionId);
    if (!Boolean.TRUE.equals(version.getEnabled()) || !supports(version, language)) {
      throw new BusinessException(ErrorCode.CODING_PROBLEM_NOT_ENABLED);
    }
    return version;
  }

  public CodingProblemVersionEntity requireVersion(Long problemVersionId) {
    CodingProblemVersionEntity version = versionMapper.selectById(problemVersionId);
    if (version == null) {
      throw new BusinessException(ErrorCode.CODING_PROBLEM_NOT_FOUND);
    }
    return version;
  }

  private CodingProblemSummaryDTO toSummary(
      CodingProblemEntity problem,
      CodingProblemVersionEntity version,
      List<String> tags
  ) {
    EnumSet<CodingLanguage> languages = EnumSet.noneOf(CodingLanguage.class);
    if (Boolean.TRUE.equals(version.getJavaEnabled())) {
      languages.add(CodingLanguage.JAVA21);
    }
    if (Boolean.TRUE.equals(version.getPythonEnabled())) {
      languages.add(CodingLanguage.PYTHON3);
    }
    return new CodingProblemSummaryDTO(
        problem.getId(), version.getId(), problem.getHotRank(), problem.getPlatformProblemId(),
        problem.getTitle(), problem.getDifficulty(), tags, problem.getSourceUrl(),
        version.getVersion(), List.copyOf(languages));
  }

  private boolean supports(CodingProblemVersionEntity version, CodingLanguage language) {
    if (language == null) {
      return true;
    }
    return language == CodingLanguage.JAVA21
        ? Boolean.TRUE.equals(version.getJavaEnabled())
        : Boolean.TRUE.equals(version.getPythonEnabled());
  }

  private <T> T read(String json, TypeReference<T> type, String error) {
    try {
      return objectMapper.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, error, e);
    }
  }

  private <T> T read(String json, Class<T> type, String error) {
    try {
      return objectMapper.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, error, e);
    }
  }
}
