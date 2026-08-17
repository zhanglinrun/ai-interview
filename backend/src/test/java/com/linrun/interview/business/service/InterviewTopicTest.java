package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("面试主题")
class InterviewTopicTest {

  @Test
  @DisplayName("空白 JD 不改主题；有 JD 时挂到 sourceJd")
  void attachesSourceJd() {
    InterviewTopic topic = new InterviewTopic(
        "java-backend", "Java 后端", "desc", List.of(), true, null, "T", "v1");

    assertThat(topic.withSourceJd("  ")).isSameAs(topic);
    assertThat(topic.withSourceJd("负责 Java 后端与 RAG 检索").sourceJd())
        .contains("RAG 检索");
    assertThat(topic.withSourceJd("负责 Java 后端与 RAG 检索").id()).isEqualTo("java-backend");
  }
}
