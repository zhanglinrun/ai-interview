package com.linrun.interview.modules.capability.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.capability.dto.EvaluationRubricDTO;
import com.linrun.interview.modules.capability.mapper.EvaluationRubricMapper;
import com.linrun.interview.modules.capability.model.CapabilityCatalogContent.RubricDimensionContent;
import com.linrun.interview.modules.capability.model.EvaluationRubricEntity;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EvaluationRubricService {

  private final EvaluationRubricMapper rubricMapper;
  private final ObjectMapper objectMapper;

  public EvaluationRubricDTO get(String rubricCode, String version) {
    EvaluationRubricEntity entity = rubricMapper.selectOne(
        Wrappers.<EvaluationRubricEntity>lambdaQuery()
            .eq(EvaluationRubricEntity::getRubricCode, rubricCode)
            .eq(EvaluationRubricEntity::getVersion, version));
    if (entity == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND,
          "Rubric 不存在: " + rubricCode + "@" + version);
    }
    try {
      List<RubricDimensionContent> dimensions = objectMapper.readValue(
          entity.getDimensionsJson(), new TypeReference<>() {
          });
      return new EvaluationRubricDTO(
          entity.getRubricCode(), entity.getVersion(), dimensions.stream()
              .map(item -> new EvaluationRubricDTO.Dimension(
                  item.code(), item.name(), item.weight(), item.criteria()))
              .toList());
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "解析 Rubric 失败", e);
    }
  }
}
