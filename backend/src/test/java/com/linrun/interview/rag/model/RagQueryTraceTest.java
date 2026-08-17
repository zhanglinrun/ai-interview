package com.linrun.interview.rag.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RAG Trace Span")
class RagQueryTraceTest {

    @Test
    @DisplayName("start 在 trace 为空时不打点")
    void startReturnsNullWhenTraceMissing() {
        assertThat(RagQueryTrace.start(null, RagQueryTrace.SPAN_INTENT, RagQueryTrace.TYPE_SPAN)).isNull();
    }

    @Test
    @DisplayName("complete 后记录耗时且 fail 不再覆盖")
    void completeRecordsLatencyAndIgnoresLaterFail() throws InterruptedException {
        RagQueryTrace trace = new RagQueryTrace();
        RagQueryTrace.Span span = RagQueryTrace.start(trace, RagQueryTrace.SPAN_REWRITE, RagQueryTrace.TYPE_SPAN);
        assertThat(span).isNotNull();
        span.input("原问题");
        Thread.sleep(15);
        span.complete("改写后");
        span.fail("should-not-overwrite");

        assertThat(span.closed()).isTrue();
        assertThat(span.status()).isEqualTo("COMPLETED");
        assertThat(span.output()).isEqualTo("改写后");
        assertThat(span.errorMessage()).isNull();
        assertThat(span.latencyMs()).isGreaterThanOrEqualTo(15L);
        assertThat(trace.spans()).containsExactly(span);
    }

    @Test
    @DisplayName("fail 将未关闭 span 标为 FAILED")
    void failMarksOpenSpan() {
        RagQueryTrace.Span span = new RagQueryTrace().startSpan(
            RagQueryTrace.SPAN_GENERATE, RagQueryTrace.TYPE_GENERATION);
        span.fail("boom");

        assertThat(span.closed()).isTrue();
        assertThat(span.status()).isEqualTo("FAILED");
        assertThat(span.errorMessage()).isEqualTo("boom");
    }
}
