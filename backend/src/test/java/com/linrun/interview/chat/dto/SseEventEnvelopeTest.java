package com.linrun.interview.chat.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SSE envelope")
class SseEventEnvelopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("进度文案应发 progress 事件而不是 retrieval")
    void progressKeepsProgressEvent() {
        SseEventEnvelope envelope = SseEventEnvelope.fromRaw(
            "t1", 2, "progress:正在改写问题...", objectMapper);

        assertThat(envelope.event()).isEqualTo("progress");
        assertThat(envelope.payload().asText()).isEqualTo("正在改写问题...");
    }
}
