package com.linrun.interview.document.service;
import com.linrun.interview.rag.constant.MetadataKeyConstant;


import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.linrun.interview.rag.constant.MetadataKeyConstant.CHUNK_ID;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExcelSplitter 测试")
class ExcelSplitterTest {

  @Test
  @DisplayName("CSV 应切块并写入 chunkId")
  void splitCsvProducesSegments() throws Exception {
    String csv = """
        name,age,city
        Alice,30,Beijing
        Bob,25,Shanghai
        """;
    ExcelSplitter splitter = new ExcelSplitter(256);

    List<TextSegment> segments = splitter.split(csv.getBytes(StandardCharsets.UTF_8));

    assertThat(segments).isNotEmpty();
    assertThat(segments.getFirst().text()).contains("Alice");
    assertThat(segments).allMatch(s -> s.metadata().getString(CHUNK_ID) != null);
  }

  @Test
  @DisplayName("小 chunkSize 应对多行 CSV 产生多块")
  void smallChunkSizeSplitsRows() throws Exception {
    String csv = """
        k,v
        a,1
        b,2
        c,3
        d,4
        """;
    ExcelSplitter splitter = new ExcelSplitter(64);

    List<TextSegment> segments = splitter.split(csv.getBytes(StandardCharsets.UTF_8));

    assertThat(segments.size()).isGreaterThan(1);
  }
}
