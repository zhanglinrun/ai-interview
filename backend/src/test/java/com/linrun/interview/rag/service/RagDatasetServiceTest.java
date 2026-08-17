package com.linrun.interview.rag.service;

import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.rag.model.DatasetGenerateResult;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RAG Dataset 评测生成")
class RagDatasetServiceTest {

  private final KnowledgeBaseQueryService queryService = mock(KnowledgeBaseQueryService.class);
  private final RagDatasetService service = new RagDatasetService(queryService);

  @BeforeEach
  void setUser() {
    UserContext.setUserId(7L);
  }

  @AfterEach
  void clearUser() {
    UserContext.clear();
  }

  @Test
  @DisplayName("generateForRagas 返回同一次检索的完整 chunk 文本")
  void generateForRagasReturnsFullChunkTexts() {
    TextSegment chunk = TextSegment.from("Redis 的五种基础类型是 String、Hash、List、Set 和 ZSet。");
    when(queryService.queryForEvaluation(List.of(11L), "Redis 有哪些数据类型？"))
        .thenReturn(new KnowledgeBaseQueryService.EvaluationQueryResult(
            "五种基础类型是 String、Hash、List、Set、ZSet。",
            List.of(chunk),
            42L,
            false));

    DatasetGenerateResult result = service.generateForRagas(List.of(11L), "Redis 有哪些数据类型？");

    assertThat(result.question()).isEqualTo("Redis 有哪些数据类型？");
    assertThat(result.answer()).contains("ZSet");
    assertThat(result.references()).containsExactly(chunk.text());
    verify(queryService).queryForEvaluation(List.of(11L), "Redis 有哪些数据类型？");
  }

  @Test
  @DisplayName("空问题或空知识库应拒绝")
  void rejectsBlankQuestionOrEmptyKb() {
    assertThatThrownBy(() -> service.generateForRagas(List.of(1L), "  "))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.generateForRagas(List.of(), "Redis 是什么"))
        .isInstanceOf(BusinessException.class);
  }
}
