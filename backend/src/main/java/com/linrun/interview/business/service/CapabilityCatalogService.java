package com.linrun.interview.business.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.business.vo.CapabilityAtomDTO;
import com.linrun.interview.business.vo.CapabilityTemplateDTO;
import com.linrun.interview.business.mapper.CapabilityAtomDefinitionMapper;
import com.linrun.interview.business.mapper.CapabilityTemplateMapper;
import com.linrun.interview.business.mapper.TemplateCapabilityMapper;
import com.linrun.interview.business.entity.CapabilityAtomDefinitionEntity;
import com.linrun.interview.business.entity.CapabilityTemplateEntity;
import com.linrun.interview.business.constant.CatalogStatus;
import com.linrun.interview.business.constant.JobTrack;
import com.linrun.interview.business.entity.TemplateCapabilityEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 已发布能力目录的只读查询入口，运行时只引用稳定业务键和版本。 */
@Service
@RequiredArgsConstructor
public class CapabilityCatalogService {

  private final CapabilityTemplateMapper templateMapper;
  private final TemplateCapabilityMapper templateCapabilityMapper;
  private final CapabilityAtomDefinitionMapper atomMapper;
  private final ObjectMapper objectMapper;

  public List<CapabilityTemplateDTO> listPublishedTemplates() {
    return templateMapper.selectList(Wrappers.<CapabilityTemplateEntity>lambdaQuery()
            .eq(CapabilityTemplateEntity::getStatus, CatalogStatus.PUBLISHED)
            .orderByAsc(CapabilityTemplateEntity::getJobTrack)
            .orderByDesc(CapabilityTemplateEntity::getEffectiveDate))
        .stream()
        .map(this::toDTO)
        .toList();
  }

  public CapabilityTemplateDTO getPublishedTemplate(JobTrack jobTrack) {
    CapabilityTemplateEntity template = templateMapper.selectOne(
        Wrappers.<CapabilityTemplateEntity>lambdaQuery()
            .eq(CapabilityTemplateEntity::getJobTrack, jobTrack)
            .eq(CapabilityTemplateEntity::getStatus, CatalogStatus.PUBLISHED)
            .orderByDesc(CapabilityTemplateEntity::getEffectiveDate)
            .orderByDesc(CapabilityTemplateEntity::getId)
            .last("LIMIT 1"));
    if (template == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "岗位能力模板尚未导入: " + jobTrack);
    }
    return toDTO(template);
  }

  public CapabilityTemplateDTO getTemplate(String templateCode, String version) {
    CapabilityTemplateEntity template = templateMapper.selectOne(
        Wrappers.<CapabilityTemplateEntity>lambdaQuery()
            .eq(CapabilityTemplateEntity::getTemplateCode, templateCode)
            .eq(CapabilityTemplateEntity::getVersion, version));
    if (template == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND,
          "能力模板不存在: " + templateCode + "@" + version);
    }
    return toDTO(template);
  }

  private CapabilityTemplateDTO toDTO(CapabilityTemplateEntity template) {
    List<TemplateCapabilityEntity> bindings = templateCapabilityMapper.selectList(
        Wrappers.<TemplateCapabilityEntity>lambdaQuery()
            .eq(TemplateCapabilityEntity::getTemplateId, template.getId())
            .orderByAsc(TemplateCapabilityEntity::getId));
    if (bindings.isEmpty()) {
      return new CapabilityTemplateDTO(
          template.getTemplateCode(), template.getJobTrack(), template.getVersion(),
          template.getContentHash(), template.getEffectiveDate(), List.of());
    }
    List<Long> atomIds = bindings.stream()
        .map(TemplateCapabilityEntity::getAtomDefinitionId)
        .distinct()
        .toList();
    Map<Long, CapabilityAtomDefinitionEntity> atoms = atomMapper.selectBatchIds(atomIds).stream()
        .collect(Collectors.toMap(CapabilityAtomDefinitionEntity::getId, Function.identity()));
    List<CapabilityAtomDTO> capabilities = new ArrayList<>();
    for (TemplateCapabilityEntity binding : bindings) {
      CapabilityAtomDefinitionEntity atom = atoms.get(binding.getAtomDefinitionId());
      if (atom == null) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR,
            "能力模板存在悬空原子引用: templateId=" + template.getId());
      }
      capabilities.add(new CapabilityAtomDTO(
          atom.getAtomId(), atom.getVersion(), atom.getName(), atom.getDescription(),
          atom.getCapabilityDomain(), atom.getParentAtomId(), binding.getDefaultWeight(),
          binding.getMinimumCoverage(), parseQuestionTypes(binding.getQuestionTypesJson())));
    }
    return new CapabilityTemplateDTO(
        template.getTemplateCode(), template.getJobTrack(), template.getVersion(),
        template.getContentHash(), template.getEffectiveDate(), capabilities);
  }

  private List<String> parseQuestionTypes(String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<>() {
      });
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "解析模板题型失败", e);
    }
  }
}
