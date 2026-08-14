package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.document.service.impl.FileHashService;
import com.linrun.interview.business.vo.CreateCodingAttemptRequest;
import com.linrun.interview.business.vo.SaveCodingDraftRequest;
import com.linrun.interview.business.mapper.CodingAttemptMapper;
import com.linrun.interview.business.mapper.CodingDraftMapper;
import com.linrun.interview.business.vo.AlgorithmCatalogContent.LanguageSpecDefinition;
import com.linrun.interview.business.entity.CodingAttemptEntity;
import com.linrun.interview.business.constant.CodingAttemptMode;
import com.linrun.interview.business.constant.CodingAttemptStatus;
import com.linrun.interview.business.entity.CodingDraftEntity;
import com.linrun.interview.business.constant.CodingLanguage;
import com.linrun.interview.business.entity.CodingProblemVersionEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("算法作答与草稿")
class CodingAttemptServiceTest {

  @Mock
  private AlgorithmCatalogService catalogService;
  @Mock
  private TestHarnessFactory harnessFactory;
  @Mock
  private CodingAttemptMapper attemptMapper;
  @Mock
  private CodingDraftMapper draftMapper;

  private CodingAttemptService service;

  @BeforeEach
  void setUp() {
    service = new CodingAttemptService(
        catalogService, harnessFactory, attemptMapper, draftMapper, new FileHashService());
  }

  @Test
  @DisplayName("创建作答时应固定题目版本并生成 revision=0 的语言模板")
  void shouldCreateAttemptAndInitialDraft() {
    CodingProblemVersionEntity version = CodingProblemVersionEntity.builder().id(5L).build();
    when(catalogService.requireEnabledVersion(5L, CodingLanguage.JAVA21)).thenReturn(version);
    when(harnessFactory.languageSpec(version, CodingLanguage.JAVA21)).thenReturn(
        new LanguageSpecDefinition(
            CodingLanguage.JAVA21, true, "solve", "INT", List.of("INT"),
            "int solve(int value)", "class Solution { int solve(int value) { return 0; } }",
            "class Solution { int solve(int value) { return value; } }"));

    var result = service.create(7L, new CreateCodingAttemptRequest(
        5L, CodingLanguage.JAVA21, CodingAttemptMode.TRAINING, "  hot100  "));

    assertThat(result.problemVersionId()).isEqualTo(5L);
    assertThat(result.contextId()).isEqualTo("hot100");
    ArgumentCaptor<CodingDraftEntity> draft = ArgumentCaptor.forClass(CodingDraftEntity.class);
    verify(draftMapper).insert(draft.capture());
    assertThat(draft.getValue().getRevision()).isZero();
    assertThat(draft.getValue().getSourceCode()).contains("class Solution");
    assertThat(draft.getValue().getCodeHash()).hasSize(64);
  }

  @Test
  @DisplayName("保存草稿应使用 expectedRevision 做乐观更新")
  void shouldSaveDraftWithExpectedRevision() {
    CodingAttemptEntity attempt = attempt();
    CodingDraftEntity updated = CodingDraftEntity.builder()
        .attemptId(8L).userId(7L).language(CodingLanguage.JAVA21)
        .sourceCode("new source").revision(3).updatedAt(LocalDateTime.now()).build();
    when(attemptMapper.selectOne(any(Wrapper.class))).thenReturn(attempt);
    when(draftMapper.updateOwnedDraft(
        eq(8L), eq(7L), eq(2), eq("new source"), any(), any(LocalDateTime.class)))
        .thenReturn(1);
    when(draftMapper.selectOne(any(Wrapper.class))).thenReturn(updated);

    var result = service.saveDraft(
        7L, "attempt-id", new SaveCodingDraftRequest(2, "new source"));

    assertThat(result.revision()).isEqualTo(3);
    assertThat(result.sourceCode()).isEqualTo("new source");
  }

  @Test
  @DisplayName("草稿版本落后时应返回冲突而不是覆盖新内容")
  void shouldRejectStaleDraftRevision() {
    when(attemptMapper.selectOne(any(Wrapper.class))).thenReturn(attempt());
    when(draftMapper.updateOwnedDraft(
        anyLong(), anyLong(), anyInt(), any(), any(), any())).thenReturn(0);

    assertThatThrownBy(() -> service.saveDraft(
        7L, "attempt-id", new SaveCodingDraftRequest(1, "stale source")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("草稿版本冲突");
  }

  @Test
  @DisplayName("已完成作答不得继续修改草稿")
  void shouldRejectDraftChangeAfterCompletion() {
    CodingAttemptEntity completed = attempt();
    completed.setStatus(CodingAttemptStatus.COMPLETED);
    when(attemptMapper.selectOne(any(Wrapper.class))).thenReturn(completed);

    assertThatThrownBy(() -> service.saveDraft(
        7L, "attempt-id", new SaveCodingDraftRequest(1, "source")))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("已结束");
  }

  private CodingAttemptEntity attempt() {
    return CodingAttemptEntity.builder()
        .id(8L)
        .attemptId("attempt-id")
        .userId(7L)
        .problemVersionId(5L)
        .language(CodingLanguage.JAVA21)
        .mode(CodingAttemptMode.TRAINING)
        .status(CodingAttemptStatus.IN_PROGRESS)
        .startedAt(LocalDateTime.now())
        .lockVersion(0)
        .build();
  }
}
