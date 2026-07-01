package com.linrun.interview.modules.knowledgebase.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.security.UserContext;
import com.linrun.interview.modules.knowledgebase.model.RagCardChoiceDTO;
import com.linrun.interview.modules.knowledgebase.rag.IntentRecognitionResult;
import com.linrun.interview.modules.knowledgebase.rag.InterviewIntent;
import com.linrun.interview.modules.resume.model.ResumeListItemDTO;
import com.linrun.interview.modules.resume.service.ResumeHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * RAG 流式交互卡片（对齐 know-engine CARD 协议，前缀 {@code card:}/{@code card_choice:}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagCardService {

  static final String CARD_PREFIX = "card:";
  static final String CARD_CHOICE_PREFIX = "card_choice:";

  private final ResumeHistoryService resumeHistoryService;
  private final ObjectMapper objectMapper;

  /**
   * 简历统计类问题缺少 resumeId 时，推送简历选择卡片。
   */
  public Optional<Flux<String>> maybeResumeSelectionCards(IntentRecognitionResult intent) {
    if (intent == null || !intent.related()
        || intent.resolvedIntent() != InterviewIntent.RESUME_STATS) {
      return Optional.empty();
    }
    if (intent.entities() != null && intent.entities().resumeId() != null) {
      return Optional.empty();
    }
    Long userId = UserContext.getUserId();
    if (userId == null) {
      return Optional.empty();
    }
    List<ResumeListItemDTO> resumes = resumeHistoryService.getAllResumes();
    if (resumes.isEmpty()) {
      return Optional.of(Flux.just(CARD_PREFIX + "您还没有上传简历，请先在「简历管理」上传后再查询。"));
    }
    if (resumes.size() == 1) {
      return Optional.empty();
    }
    List<RagCardChoiceDTO> choices = new ArrayList<>(resumes.size());
    for (ResumeListItemDTO resume : resumes) {
      String label = resume.filename() != null && !resume.filename().isBlank()
          ? resume.filename()
          : "简历 #" + resume.id();
      choices.add(new RagCardChoiceDTO(String.valueOf(resume.id()), label, "resume"));
    }
    try {
      String json = objectMapper.writeValueAsString(choices);
      return Optional.of(Flux.just(
          CARD_PREFIX + "请先选择要查询的简历",
          CARD_CHOICE_PREFIX + json));
    } catch (JsonProcessingException e) {
      log.warn("序列化简历卡片失败: {}", e.getMessage(), e);
      return Optional.empty();
    }
  }
}
