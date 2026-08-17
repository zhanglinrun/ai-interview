package com.linrun.interview.rag.controller;

import com.linrun.interview.common.result.Result;
import com.linrun.interview.rag.model.DatasetGenerateRequest;
import com.linrun.interview.rag.model.DatasetGenerateResult;
import com.linrun.interview.rag.service.RagDatasetService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DatasetController")
class DatasetControllerTest {

  private final RagDatasetService ragDatasetService = mock(RagDatasetService.class);
  private final DatasetController controller = new DatasetController(ragDatasetService);

  @Test
  @DisplayName("GET/POST 都委托 generateForRagas，返回 question/answer/references")
  void getAndPostDelegateToService() {
    DatasetGenerateResult payload = new DatasetGenerateResult(
        "覆盖索引是什么？",
        "查询列都在索引里，不必回表。",
        List.of("覆盖索引是指查询所需列全部包含在索引中，InnoDB 不必回表。"));
    when(ragDatasetService.generateForRagas(List.of(3L), "覆盖索引是什么？"))
        .thenReturn(payload);

    Result<DatasetGenerateResult> getResult =
        controller.generateGet("覆盖索引是什么？", List.of(3L));
    Result<DatasetGenerateResult> postResult =
        controller.generatePost(new DatasetGenerateRequest("覆盖索引是什么？", List.of(3L)));

    assertThat(getResult.getData()).isEqualTo(payload);
    assertThat(postResult.getData().references()).hasSize(1);
    verify(ragDatasetService, times(2)).generateForRagas(List.of(3L), "覆盖索引是什么？");
  }
}
