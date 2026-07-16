package com.linrun.interview.common.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("证据范围隔离契约")
class EvidenceScopeTest {

  @Test
  @DisplayName("候选人资料范围固定 owner、domain 和资源")
  void candidateScopeMatchesOnlyExplicitResources() {
    EvidenceScope scope = EvidenceScope.candidateKnowledgeBases(7L, List.of(11L, 12L));

    assertThat(scope.contains(DataDomain.CANDIDATE, "11", "3", 7L)).isTrue();
    assertThat(scope.contains(DataDomain.CANDIDATE, "13", "3", 7L)).isFalse();
    assertThat(scope.contains(DataDomain.CANDIDATE, "11", "3", 8L)).isFalse();
    assertThat(scope.contains(DataDomain.JOB, "11", "3", 7L)).isFalse();
  }

  @Test
  @DisplayName("关闭个人资料后拒绝 CANDIDATE 域")
  void rejectsPersonalDomainWhenDisabled() {
    assertThatThrownBy(() -> new EvidenceScope(
        7L,
        List.of(new EvidenceScope.DomainScope(
            DataDomain.CANDIDATE, Set.of("resume-1"), Set.of(), 1.0d)),
        false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("平台 owner 必须为 0")
  void platformOwnerIsExplicit() {
    assertThatThrownBy(() -> new EvidenceMetadata(
        7L, DataDomain.PLATFORM, "guide", "v1", "e1", "hash", "DOC", "p1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new EvidenceMetadata(
        0L, DataDomain.PLATFORM, "guide", "v1", "e1", "hash", "DOC", "p1")
        .ownerUserId()).isZero();
  }
}
