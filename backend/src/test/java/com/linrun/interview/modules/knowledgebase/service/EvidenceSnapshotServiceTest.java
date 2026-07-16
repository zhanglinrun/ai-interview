package com.linrun.interview.modules.knowledgebase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.evidence.DataDomain;
import com.linrun.interview.common.evidence.EvidenceCandidate;
import com.linrun.interview.common.evidence.EvidencePacket;
import com.linrun.interview.common.evidence.EvidenceRef;
import com.linrun.interview.common.evidence.EvidenceStatus;
import com.linrun.interview.modules.knowledgebase.mapper.EvidenceSnapshotMapper;
import com.linrun.interview.modules.knowledgebase.mapper.EvidenceSnapshotRefMapper;
import com.linrun.interview.modules.knowledgebase.model.EvidenceSnapshotEntity;
import com.linrun.interview.modules.knowledgebase.model.EvidenceSnapshotRefEntity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("证据快照源删除语义")
class EvidenceSnapshotServiceTest {

  private final EvidenceSnapshotMapper snapshotMapper = mock(EvidenceSnapshotMapper.class);
  private final EvidenceSnapshotRefMapper refMapper = mock(EvidenceSnapshotRefMapper.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final EvidenceSnapshotService service = new EvidenceSnapshotService(
      snapshotMapper, refMapper, objectMapper);

  @Test
  @DisplayName("源资料删除后清空正文和定位链接但保留不可还原的审计元数据")
  void shouldRedactSnapshotWhenSourceIsDeleted() throws Exception {
    String packetJson = objectMapper.writeValueAsString(new EvidencePacket(
        "java.concurrent",
        "线程池为什么使用有界队列",
        EvidenceStatus.SUFFICIENT,
        List.of(new EvidenceCandidate(
            new EvidenceRef(
                "github:repo-1:src/App.java:1-8",
                DataDomain.GITHUB,
                "github-repository:9",
                "a".repeat(40),
                "GITHUB_FILE",
                "https://github.com/demo/repo/blob/" + "a".repeat(40) + "/src/App.java#L1-L8",
                "b".repeat(64)),
            "private class App { String secret = \"candidate source\"; }",
            0.91d,
            0.88d,
            1.2d,
            1),
            new EvidenceCandidate(
                new EvidenceRef(
                    "platform:spring-tx",
                    DataDomain.PLATFORM,
                    "spring-transaction-management",
                    "2026-07-15",
                    "OFFICIAL_DOCUMENTATION",
                    "https://docs.spring.io/spring-framework/reference/data-access/transaction.html",
                    "c".repeat(64)),
                "平台审核资料应继续可复核",
                0.82d,
                0.79d,
                1.1d,
                2)),
        List.of(),
        List.of("RERANK_UNAVAILABLE")));

    EvidenceSnapshotRefEntity ref = new EvidenceSnapshotRefEntity();
    ref.setSnapshotId("evidence-1");
    EvidenceSnapshotEntity snapshot = new EvidenceSnapshotEntity();
    snapshot.setId(1L);
    snapshot.setUserId(7L);
    snapshot.setSnapshotId("evidence-1");
    snapshot.setCapabilityAtomKey("java.concurrent");
    snapshot.setQueryText("候选人私有 JD 要求：熟悉线程池");
    snapshot.setEvidenceStatus(EvidenceStatus.SUFFICIENT);
    snapshot.setPacketJson(packetJson);
    snapshot.setSourceAvailable(true);
    when(refMapper.selectList(any())).thenReturn(List.of(ref));
    when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot));
    when(snapshotMapper.updateById(any(EvidenceSnapshotEntity.class))).thenReturn(1);

    int updated = service.markSourceUnavailable(
        7L, DataDomain.GITHUB, "github-repository:9", "a".repeat(40));

    assertThat(updated).isEqualTo(1);
    ArgumentCaptor<EvidenceSnapshotEntity> captor =
        ArgumentCaptor.forClass(EvidenceSnapshotEntity.class);
    verify(snapshotMapper).updateById(captor.capture());
    EvidenceSnapshotEntity redactedEntity = captor.getValue();
    assertThat(redactedEntity.getSourceAvailable()).isFalse();
    assertThat(redactedEntity.getQueryText()).isEqualTo("源资料已删除，无法复核");
    assertThat(redactedEntity.getPacketJson())
        .doesNotContain("candidate source")
        .doesNotContain("github.com/demo/repo")
        .contains("平台审核资料应继续可复核")
        .contains("docs.spring.io")
        .contains("unavailable://deleted")
        .contains("SOURCE_DELETED_UNVERIFIABLE");

    EvidencePacket redacted = objectMapper.readValue(
        redactedEntity.getPacketJson(), EvidencePacket.class);
    assertThat(redacted.query()).isEqualTo("源资料已删除，无法复核");
    assertThat(redacted.candidates()).hasSize(2);
    assertThat(redacted.candidates().getFirst())
        .satisfies(candidate -> {
          assertThat(candidate.text()).isEmpty();
          assertThat(candidate.ref().sourceLocator()).isEqualTo("unavailable://deleted");
          assertThat(candidate.ref().resourceId()).isEqualTo("github-repository:9");
          assertThat(candidate.ref().resourceVersion()).isEqualTo("a".repeat(40));
          assertThat(candidate.ref().contentHash()).isEqualTo("b".repeat(64));
        });
    assertThat(redacted.candidates().get(1).text())
        .isEqualTo("平台审核资料应继续可复核");
    assertThat(redacted.degradedReasons())
        .containsExactly("RERANK_UNAVAILABLE", "SOURCE_DELETED_UNVERIFIABLE");
  }
}
