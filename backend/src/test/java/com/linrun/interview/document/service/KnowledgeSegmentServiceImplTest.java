package com.linrun.interview.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.document.constant.SegmentStatus;
import com.linrun.interview.document.mapper.KnowledgeBaseSegmentMapper;
import com.linrun.interview.document.entity.KnowledgeBaseSegmentEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("知识库分段服务测试")
class KnowledgeSegmentServiceImplTest {

  @Mock private KnowledgeBaseSegmentMapper segmentMapper;
  @Mock private StringRedisTemplate stringRedisTemplate;
  @Mock private ObjectMapper objectMapper;

  @InjectMocks
  private KnowledgeSegmentServiceImpl service;

  @Test
  @DisplayName("批量保存分段时应自动填充 createdAt/updatedAt")
  void saveBatchFillsTimestamps() {
    KnowledgeBaseSegmentEntity segment = new KnowledgeBaseSegmentEntity();
    segment.setText("hello");
    segment.setDocumentId(1L);
    segment.setDocumentVersion(2L);
    segment.setStatus(SegmentStatus.STORED);
    when(segmentMapper.insert(any(KnowledgeBaseSegmentEntity.class))).thenReturn(1);

    service.saveSegments(List.of(segment));

    ArgumentCaptor<KnowledgeBaseSegmentEntity> captor =
        ArgumentCaptor.forClass(KnowledgeBaseSegmentEntity.class);
    verify(segmentMapper).insert(captor.capture());
    assertThat(captor.getValue().getCreatedAt()).isNotNull();
    assertThat(captor.getValue().getUpdatedAt()).isNotNull();
  }

  @Test
  @DisplayName("批量回写 embedding 时应携带文档和版本作用域")
  void batchUpdateEmbeddingUsesDocumentVersionScope() {
    KnowledgeBaseSegmentEntity segment = new KnowledgeBaseSegmentEntity();
    segment.setId(1L);
    segment.setEmbeddingId("kb-segment-1");
    LocalDateTime claimedAt = LocalDateTime.of(2026, 7, 19, 12, 0);
    when(segmentMapper.batchUpdateEmbedding(
        any(), eq(10L), eq(20L), eq(2), eq(claimedAt),
        eq(SegmentStatus.VECTOR_STORED.name())))
        .thenReturn(1);

    int affected = service.batchUpdateEmbedding(10L, 20L, 2, claimedAt, List.of(segment));

    assertThat(affected).isEqualTo(1);
    verify(segmentMapper).batchUpdateEmbedding(
        List.of(segment), 10L, 20L, 2, claimedAt, SegmentStatus.VECTOR_STORED.name());
  }
}
