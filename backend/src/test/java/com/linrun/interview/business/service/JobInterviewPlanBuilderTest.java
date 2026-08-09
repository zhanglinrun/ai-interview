package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.rag.model.EvidenceCandidate;
import com.linrun.interview.rag.model.EvidenceRef;
import com.linrun.interview.github.dto.GithubEvidenceCardDTO;
import com.linrun.interview.business.constant.JobInterviewStage;
import com.linrun.interview.business.job.JobCapabilityMappingEntity;
import java.util.List;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("岗位实战真实面试题面")
class JobInterviewPlanBuilderTest {

  @Test
  @DisplayName("算法题作为最后阶段出现")
  void shouldKeepAlgorithmAtTheEnd() {
    assertThat(JobInterviewStage.values()).containsExactly(
        JobInterviewStage.PROJECT_DEEP_DIVE,
        JobInterviewStage.POSITION_TECH,
        JobInterviewStage.ENGINEERING_SCENARIO,
        JobInterviewStage.ALGORITHM);
  }

  @Test
  @DisplayName("项目首题先了解真实背景和职责，不暴露内部出题指令")
  void shouldStartWithNaturalProjectContextQuestion() {
    String question = JobInterviewPlanBuilder.questionText(
        mapping("RAG_DOCUMENT_PIPELINE", "RAG 文档处理链路"),
        JobInterviewStage.PROJECT_DEEP_DIVE,
        0,
        null);

    assertThat(question)
        .contains("学习或实习经历", "项目背景", "整体架构", "团队分工", "负责的部分")
        .doesNotContain("目标岗位", "强调「", "围绕“", "目标能力原子", "讲清调用链", "验证证据");
  }

  @Test
  @DisplayName("Agent 和 RAG 主问题直接询问真实业务与技术实现")
  void shouldAskDirectImplementationQuestions() {
    String agentQuestion = JobInterviewPlanBuilder.questionText(
        mapping("AGENT_ORCHESTRATION", "Agent 编排"),
        JobInterviewStage.POSITION_TECH,
        0,
        null);
    String ragQuestion = JobInterviewPlanBuilder.questionText(
        mapping("RAG_RETRIEVAL", "检索与证据编排"),
        JobInterviewStage.POSITION_TECH,
        1,
        null);

    assertThat(agentQuestion)
        .contains("解决什么业务问题", "一次真实请求")
        .doesNotContain("显式状态机", "Plan-and-Execute", "Reflection", "讲清", "验证证据");
    assertThat(ragQuestion)
        .contains("自研还是基于框架", "召回方式", "重排")
        .doesNotContain("目标岗位", "围绕“", "要求候选人");
  }

  @Test
  @DisplayName("GitHub 证据问题可以具体到代码，但不显示证据系统术语")
  void shouldUseNaturalGithubQuestion() {
    GithubEvidenceCardDTO card = new GithubEvidenceCardDTO(
        "PROJECT_TROUBLESHOOTING",
        "1.0.0",
        "项目深挖",
        "WEAK",
        0.7d,
        "",
        "我看到项目的 src/OrderService.java 里有 updateStock 这段实现。"
            + "它在整个业务链路中负责什么？当时为什么这样设计？",
        List.of());

    String question = JobInterviewPlanBuilder.questionText(
        mapping("PROJECT_TROUBLESHOOTING", "项目深挖"),
        JobInterviewStage.PROJECT_DEEP_DIVE,
        1,
        card);

    assertThat(question)
        .contains("OrderService.java", "updateStock", "业务链路")
        .doesNotContain("固定提交", "evidence", "验证证据");
  }

  @Test
  @DisplayName("GitHub 证据卡引用并入题目专属证据候选")
  void shouldMergeGithubRefsIntoQuestionEvidence() {
    EvidenceRef ref = new EvidenceRef(
        "gh-evidence-1", DataDomain.GITHUB, "github-repository:9", "a".repeat(40),
        "GITHUB_CODE", "https://github.com/demo/repo/blob/a/src/App.java#L1-L3",
        "b".repeat(64));
    GithubEvidenceCardDTO card = new GithubEvidenceCardDTO(
        "RAG_RETRIEVAL", "1.0.0", "检索", "WEAK", 0.7d, "", "问题", List.of(ref));
    var candidates = new LinkedHashMap<String, EvidenceCandidate>();

    JobInterviewPlanBuilder.addGithubCandidates(candidates, card);

    assertThat(candidates).containsKey("gh-evidence-1");
    assertThat(candidates.get("gh-evidence-1").ref().dataDomain()).isEqualTo(DataDomain.GITHUB);
    assertThat(candidates.get("gh-evidence-1").text()).contains("固定 SHA 代码证据");
  }

  @Test
  @DisplayName("冻结题面不预设候选人做过某个项目或亲历过故障")
  void shouldNotAssumeUnstatedProjectExperience() {
    List<String> atomIds = List.of(
        "SPRING_APPLICATION",
        "PROJECT_TROUBLESHOOTING",
        "CACHE_DISTRIBUTED",
        "MESSAGE_RELIABILITY",
        "RAG_DOCUMENT_PIPELINE",
        "RAG_RETRIEVAL",
        "RAG_EVALUATION",
        "AGENT_ORCHESTRATION",
        "LLM_APPLICATION_ENGINEERING",
        "AI_APPLICATION_RELIABILITY");

    List<String> questions = atomIds.stream()
        .flatMap(atomId -> List.of(
            JobInterviewPlanBuilder.questionText(
                mapping(atomId, atomId), JobInterviewStage.PROJECT_DEEP_DIVE, 1, null),
            JobInterviewPlanBuilder.questionText(
                mapping(atomId, atomId), JobInterviewStage.POSITION_TECH, 0, null)
        ).stream())
        .toList();

    assertThat(questions)
        .allSatisfy(question -> assertThat(question)
            .doesNotContain(
                "你遇到过最难定位",
                "你们使用什么缓存模式",
                "你们的 RAG 检索",
                "你的 RAG 文档",
                "选一个你亲自排查过"));
  }

  private JobCapabilityMappingEntity mapping(String atomId, String name) {
    return JobCapabilityMappingEntity.builder()
        .atomId(atomId)
        .atomVersion("1.0.0")
        .capabilityName(name)
        .build();
  }
}
