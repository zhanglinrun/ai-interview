package com.linrun.interview.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.rag.mapper.RagAnswerSnapshotMapper;
import com.linrun.interview.rag.mapper.RagTraceCandidateMapper;
import com.linrun.interview.rag.mapper.RagTraceCitationMapper;
import com.linrun.interview.rag.mapper.RagTraceRunMapper;
import com.linrun.interview.rag.mapper.RagTraceStageMapper;
import com.linrun.interview.rag.model.RagQueryTrace;
import com.linrun.interview.rag.model.RagTraceStageEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("RAG Trace 过程打点落库")
class RagTraceRecorderTest {

    @Test
    @DisplayName("有 span 时写入真实 latencyMs 和 observationType")
    void writesTimedStageFromSpan() throws Exception {
        RagTraceStageMapper stageMapper = mock(RagTraceStageMapper.class);
        RagTraceRecorder recorder = recorder(stageMapper);

        RagQueryTrace trace = new RagQueryTrace();
        RagQueryTrace.Span span = trace.startSpan(RagQueryTrace.SPAN_REWRITE, RagQueryTrace.TYPE_SPAN);
        span.input("RocketMQ 是什么");
        Thread.sleep(20);
        span.complete("RocketMQ 核心原理");

        recorder.recordSnapshot("trace-1", 1L, "s1", List.of(1L), "RocketMQ 是什么",
            trace, List.of(), "回答", 0.9, List.of(), 100L);

        ArgumentCaptor<RagTraceStageEntity> captor = ArgumentCaptor.forClass(RagTraceStageEntity.class);
        verify(stageMapper).insert(captor.capture());
        RagTraceStageEntity entity = captor.getValue();
        assertThat(entity.getStage()).isEqualTo("REWRITE");
        assertThat(entity.getLatencyMs()).isGreaterThanOrEqualTo(20L);
        assertThat(entity.getInputSummary()).isEqualTo("RocketMQ 是什么");
        assertThat(entity.getOutputSummary()).isEqualTo("RocketMQ 核心原理");
        assertThat(entity.getMetadataJson()).contains("\"observationType\":\"span\"");
        assertThat(entity.getStartedAt()).isNotNull();
        assertThat(entity.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("未关闭的 span 落库前补成 DEGRADED")
    void autoClosesOpenSpanAsDegraded() {
        RagTraceStageMapper stageMapper = mock(RagTraceStageMapper.class);
        RagTraceRecorder recorder = recorder(stageMapper);
        RagQueryTrace trace = new RagQueryTrace();
        RagQueryTrace.Span span = trace.startSpan(RagQueryTrace.SPAN_GENERATE, RagQueryTrace.TYPE_GENERATION);
        span.input("q");

        recorder.recordSnapshot("trace-1", 1L, "s1", List.of(1L), "q",
            trace, List.of(), "回答", null, List.of(), 50L);

        assertThat(span.closed()).isTrue();
        assertThat(span.status()).isEqualTo("DEGRADED");
        ArgumentCaptor<RagTraceStageEntity> captor = ArgumentCaptor.forClass(RagTraceStageEntity.class);
        verify(stageMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("DEGRADED");
        assertThat(captor.getValue().getLatencyMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("无 span 时仍写快照且阶段耗时为 0")
    void legacySnapshotKeepsZeroLatency() {
        RagTraceStageMapper stageMapper = mock(RagTraceStageMapper.class);
        RagTraceRecorder recorder = recorder(stageMapper);
        RagQueryTrace trace = new RagQueryTrace();
        trace.route("knowledge_base", "TECH_KB", 0.9, "heuristic");
        trace.rewrittenQuestion("RocketMQ 核心原理");

        recorder.recordSnapshot("trace-1", 1L, "s1", List.of(1L), "RocketMQ 是什么",
            trace, List.of(), "回答", 0.8, List.of(), 80L);

        ArgumentCaptor<RagTraceStageEntity> captor = ArgumentCaptor.forClass(RagTraceStageEntity.class);
        verify(stageMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        assertThat(captor.getAllValues())
            .isNotEmpty()
            .allMatch(stage -> Long.valueOf(0L).equals(stage.getLatencyMs()));
    }

    private static RagTraceRecorder recorder(RagTraceStageMapper stageMapper) {
        return new RagTraceRecorder(
            mock(RagTraceRunMapper.class),
            stageMapper,
            mock(RagTraceCandidateMapper.class),
            mock(RagTraceCitationMapper.class),
            mock(RagAnswerSnapshotMapper.class),
            new ObjectMapper());
    }
}
