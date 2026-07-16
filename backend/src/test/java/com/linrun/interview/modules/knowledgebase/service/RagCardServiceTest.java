package com.linrun.interview.modules.knowledgebase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.modules.interview.service.InterviewPersistenceService;
import com.linrun.interview.modules.interview.topic.InterviewTopic;
import com.linrun.interview.modules.interview.topic.InterviewTopicCatalog;
import com.linrun.interview.modules.interviewschedule.service.InterviewScheduleService;
import com.linrun.interview.modules.knowledgebase.rag.IntentRecognitionResult;
import com.linrun.interview.modules.knowledgebase.rag.InterviewIntent;
import com.linrun.interview.modules.resume.service.ResumeHistoryService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("RAG 交互卡片测试")
class RagCardServiceTest {

  private final ResumeHistoryService resumeHistoryService = mock(ResumeHistoryService.class);
  private final InterviewScheduleService interviewScheduleService = mock(InterviewScheduleService.class);
  private final InterviewPersistenceService interviewPersistenceService = mock(InterviewPersistenceService.class);
  private final InterviewTopicCatalog interviewTopicCatalog = mock(InterviewTopicCatalog.class);
  private final RagCardService service = new RagCardService(
      resumeHistoryService,
      interviewScheduleService,
      interviewPersistenceService,
      interviewTopicCatalog,
      new ObjectMapper());

  @Test
  @DisplayName("项目深挖训练题不应被岗位或历史报告选择卡片截断")
  void shouldNotInterceptProjectDeepDiveTrainingQuestion() {
    IntentRecognitionResult intent = interviewPrepIntent();

    assertThat(service.maybeInteractionCards(
        intent,
        "围绕面试项目深挖与故障定位，选择一个真实项目实现，讲清调用链、关键取舍和验证证据。"))
        .isEmpty();
    verifyNoInteractions(interviewPersistenceService, interviewTopicCatalog);
  }

  @Test
  @DisplayName("明确请求模拟面试方向时应返回 jobTrack 选择卡片")
  void shouldReturnJobTrackChoicesForExplicitTopicRequest() {
    when(interviewTopicCatalog.listTopics()).thenReturn(List.of(
        topic("java-backend", "Java 后端"),
        topic("ai-rag-agent", "AI / RAG Agent")));

    List<String> events = service.maybeInteractionCards(
            interviewPrepIntent(), "我想开始一场模拟面试，请选择岗位方向")
        .orElseThrow()
        .collectList()
        .block();

    assertThat(events).containsExactly(
        "card:请选择目标岗位方向",
        "card_choice:[{\"id\":\"java-backend\",\"label\":\"Java 后端\",\"type\":\"jobTrack\"},"
            + "{\"id\":\"ai-rag-agent\",\"label\":\"AI / RAG Agent\",\"type\":\"jobTrack\"}]");
  }

  @Test
  @DisplayName("泛化面试准备问题不应在查询历史会话前被报告卡片截断")
  void shouldNotLoadReportsWithoutExplicitReportRequest() {
    assertThat(service.maybeInterviewSessionSelectionCards(
        interviewPrepIntent(), "请根据知识库讲清这段调用链"))
        .isEmpty();
    verifyNoInteractions(interviewPersistenceService);
  }

  private IntentRecognitionResult interviewPrepIntent() {
    return new IntentRecognitionResult(
        "模拟面试相关",
        true,
        InterviewIntent.INTERVIEW_PREP.name(),
        new IntentRecognitionResult.Entities(null, null, null, null));
  }

  private InterviewTopic topic(String id, String name) {
    return new InterviewTopic(id, name, "", List.of(), true, null, null, null);
  }
}
