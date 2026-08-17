package com.linrun.interview.rag.service;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("参考资料注入")
class InterviewContentInjectorTest {

  @Test
  @DisplayName("应按编号注入参考资料而不是英文 context 套话")
  void injectsNumberedReferences() {
    UserMessage injected = (UserMessage) new InterviewContentInjector().inject(
        List.of(Content.from(TextSegment.from("HashMap 不是线程安全的"))),
        UserMessage.from("HashMap 能保证并发安全吗？"));
    String text = injected.singleText();
    assertThat(text).contains("用户问题：HashMap 能保证并发安全吗？");
    assertThat(text).contains("参考资料：");
    assertThat(text).contains("[1]");
    assertThat(text).contains("HashMap 不是线程安全的");
    assertThat(text).doesNotContain("Answer using the following information");
  }

  @Test
  @DisplayName("注入前应去掉图片和裸 URL，保留正文")
  void stripsImagesBeforeInject() {
    UserMessage injected = (UserMessage) new InterviewContentInjector().inject(
        List.of(Content.from(TextSegment.from(
            "MySQL 是一个开源的关系型数据库。\n![](http://localhost:29000/ai-interview/converted/1.jpg)\n怎么删除表？"))),
        UserMessage.from("什么是 MySQL?"));
    String text = injected.singleText();
    assertThat(text).contains("MySQL 是一个开源的关系型数据库");
    assertThat(text).doesNotContain("localhost:29000");
    assertThat(text).doesNotContain("![]");
  }
}
