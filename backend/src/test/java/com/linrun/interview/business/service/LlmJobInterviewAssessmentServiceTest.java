package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.ai.service.LlmProviderRegistry;
import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.rag.model.EvidenceCandidate;
import com.linrun.interview.rag.model.EvidencePacket;
import com.linrun.interview.rag.model.EvidenceRef;
import com.linrun.interview.rag.model.EvidenceStatus;
import com.linrun.interview.github.client.GithubEvidenceReader;
import com.linrun.interview.github.client.GithubUntrustedEvidenceFormatter;
import com.linrun.interview.business.entity.JobInterviewQuestionEntity;
import com.linrun.interview.business.entity.JobInterviewSessionEntity;
import com.linrun.interview.business.constant.RecommendedAction;
import com.linrun.interview.rag.model.EvidenceSnapshotEntity;
import com.linrun.interview.rag.service.EvidenceSnapshotService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("岗位实战 BYOK 单题评价")
class LlmJobInterviewAssessmentServiceTest {

  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private EvidenceSnapshotService evidenceSnapshotService;
  @Mock
  private GithubEvidenceReader githubEvidenceReader;
  @Mock
  private ChatModel chatModel;

  private LlmJobInterviewAssessmentService service;

  @BeforeEach
  void setUp() {
    service = new LlmJobInterviewAssessmentService(
        llmProviderRegistry, evidenceSnapshotService, githubEvidenceReader,
        new GithubUntrustedEvidenceFormatter(),
        new ObjectMapper().findAndRegisterModules());
  }

  @Test
  @DisplayName("无项目证据仍可评价通用技术内容，但事实一致性必须是 UNVERIFIED")
  void shouldSeparateTechnicalScoreFromFactualVerification() {
    when(llmProviderRegistry.getUserChatModel(7L)).thenReturn(chatModel);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
        .aiMessage(AiMessage.from("""
            {"technicalCorrectness":88,"completeness":76,
             "factualConsistency":"CONSISTENT","confidence":0.91,
             "recommendedAction":"DEEPEN","rationale":"主链路正确",
             "followUpQuestion":"异常恢复时如何保证幂等？"}
            """))
        .tokenUsage(new TokenUsage(120, 45))
        .build());

    var outcome = service.assess(7L, session(), question(), "使用状态机和幂等键收敛");

    assertThat(outcome.assessment().technicalCorrectness()).isEqualTo(88);
    assertThat(outcome.assessment().factualConsistency()).isEqualTo("UNVERIFIED");
    assertThat(outcome.assessment().evidenceStatus()).isEqualTo(EvidenceStatus.NONE);
    assertThat(outcome.assessment().pendingReview()).isFalse();
    assertThat(outcome.assessment().recommendedAction()).isEqualTo(RecommendedAction.DEEPEN);
    assertThat(outcome.followUpQuestion()).contains("幂等");
    assertThat(outcome.inputTokens()).isEqualTo(120);
    assertThat(outcome.outputTokens()).isEqualTo(45);
    ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
    verify(chatModel).chat(request.capture());
    SystemMessage systemMessage = (SystemMessage) request.getValue().messages().getFirst();
      assertThat(systemMessage.text())
          .contains("真实出现的一个具体事实", "只继续问一个重点", "像真实面试官直接提问")
          .contains("不能复述 JD", "不能出现“目标岗位强调”")
          .contains("不得要求其讲“你遇到过的真实故障”", "必须改为")
          .contains("如果实际遇到过类似问题", "如果没有，请说明你会如何排查")
          .contains("不能把尚未说明的行动", "写成既成事实")
          .contains("不能", "假定其调整过检索权重、重排序策略或已经得到提升数据")
          .contains("不得追问", "效果提升了多少");
  }

  @Test
  @DisplayName("BYOK 不可用时重试有界并保留待复核事实，不伪造分数")
  void shouldPersistPendingAssessmentWhenByokUnavailable() {
    when(llmProviderRegistry.getUserChatModel(7L))
        .thenThrow(new IllegalStateException("provider unavailable"));

    var outcome = service.assess(7L, session(), question(), "候选人回答");

    assertThat(outcome.assessment().pendingReview()).isTrue();
    assertThat(outcome.assessment().technicalCorrectness()).isNull();
    assertThat(outcome.degradedReason()).isEqualTo("AI_EVALUATION_UNAVAILABLE");
    assertThat(outcome.retryCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("项目深挖评价会按 evidenceId 调用 GitHub MCP 复核并保留固定快照降级")
  void shouldVerifyGithubEvidenceDuringRealAssessment() throws Exception {
    String commit = "a".repeat(40);
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    EvidencePacket packet = new EvidencePacket(
        "java.transaction",
        "事务边界如何设计",
        EvidenceStatus.WEAK,
        List.of(
            new EvidenceCandidate(
                new EvidenceRef(
                    "gh-evidence-1",
                    DataDomain.GITHUB,
                    "github-repository:9",
                    commit,
                    "GITHUB_CODE",
                    "https://github.com/demo/repo/blob/" + commit + "/src/App.java#L2-L3",
                    "b".repeat(64)),
                "冻结快照片段",
                0.8d,
                0.7d,
                1.2d,
                1),
            new EvidenceCandidate(
                new EvidenceRef(
                    "gh-evidence-2",
                    DataDomain.GITHUB,
                    "github-repository:9",
                    commit,
                    "GITHUB_CODE",
                    "https://github.com/demo/repo/blob/" + commit + "/src/OrderService.java#L5-L7",
                    "c".repeat(64)),
                "冻结订单服务片段",
                0.7d,
                0.6d,
                1.1d,
                2)),
        List.of(),
        List.of());
    EvidenceSnapshotEntity snapshot = new EvidenceSnapshotEntity();
    snapshot.setEvidenceStatus(EvidenceStatus.WEAK);
    snapshot.setPacketJson(mapper.writeValueAsString(packet));
    snapshot.setSourceAvailable(true);
    when(evidenceSnapshotService.get(7L, "snapshot-1")).thenReturn(snapshot);
    when(githubEvidenceReader.readEvidence(7L, 9L, commit, "gh-evidence-1"))
        .thenReturn(new GithubEvidenceReader.ReadResult(
            "line 2\nline 3", "MCP", commit, "src/App.java", 2, true));
    when(githubEvidenceReader.readEvidence(7L, 9L, commit, "gh-evidence-2"))
        .thenReturn(new GithubEvidenceReader.ReadResult(
            "line 5\nline 7", "SNAPSHOT", commit, "src/OrderService.java", 5, true));
    when(llmProviderRegistry.getUserChatModel(7L)).thenReturn(chatModel);
    when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
        .aiMessage(AiMessage.from("""
            {"technicalCorrectness":80,"completeness":72,
             "factualConsistency":"CONSISTENT","confidence":0.86,
             "recommendedAction":"DEEPEN","rationale":"代码与回答一致",
             "followUpQuestion":"失败补偿如何设计？"}
            """))
        .build());
    JobInterviewSessionEntity session = JobInterviewSessionEntity.builder()
        .id(11L).userId(7L).sessionId("session-1")
        .githubRepositoryId(9L).githubCommitSha(commit).build();
    JobInterviewQuestionEntity question = JobInterviewQuestionEntity.builder()
        .id(21L).userId(7L).sessionId(11L)
        .questionText("事务边界如何设计？")
        .evidenceSnapshotId("snapshot-1")
        .build();

    var outcome = service.assess(7L, session, question, "外部调用在事务外，落库使用短事务");

    verify(githubEvidenceReader).readEvidence(7L, 9L, commit, "gh-evidence-1");
    verify(githubEvidenceReader).readEvidence(7L, 9L, commit, "gh-evidence-2");
    assertThat(outcome.assessment().evidenceStatus()).isEqualTo(EvidenceStatus.WEAK);
    assertThat(outcome.assessment().factualConsistency()).isEqualTo("CONSISTENT");
    assertThat(outcome.assessment().objectiveEvidenceIds())
        .containsExactly("gh-evidence-1", "gh-evidence-2");
  }

  private JobInterviewSessionEntity session() {
    return JobInterviewSessionEntity.builder()
        .id(11L).userId(7L).sessionId("session-1").build();
  }

  private JobInterviewQuestionEntity question() {
    return JobInterviewQuestionEntity.builder()
        .id(21L).userId(7L).sessionId(11L)
        .questionText("消息重复消费时如何保证最终一致？")
        .build();
  }
}
