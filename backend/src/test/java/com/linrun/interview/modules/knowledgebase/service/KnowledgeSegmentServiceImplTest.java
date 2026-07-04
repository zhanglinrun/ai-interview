package com.linrun.interview.modules.knowledgebase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.modules.knowledgebase.constant.SegmentStatus;
import com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseSegmentMapper;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    service.saveBatch(List.of(segment));

    ArgumentCaptor<KnowledgeBaseSegmentEntity> captor =
        ArgumentCaptor.forClass(KnowledgeBaseSegmentEntity.class);
    verify(segmentMapper).insert(captor.capture());
    assertThat(captor.getValue().getCreatedAt()).isNotNull();
    assertThat(captor.getValue().getUpdatedAt()).isNotNull();
  }
}
