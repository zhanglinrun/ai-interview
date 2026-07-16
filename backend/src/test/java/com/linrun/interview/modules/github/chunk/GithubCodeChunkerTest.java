package com.linrun.interview.modules.github.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.linrun.interview.modules.github.config.GithubEvidenceProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GitHub 代码感知切块")
class GithubCodeChunkerTest {

  private GithubEvidenceProperties properties;
  private GithubCodeChunker chunker;

  @BeforeEach
  void setUp() {
    properties = new GithubEvidenceProperties();
    properties.setChunkMaxLines(120);
    properties.setChunkOverlapLines(20);
    chunker = new GithubCodeChunker(properties);
  }

  @Test
  @DisplayName("Java 按类、接口和方法边界保留准确行号")
  void shouldChunkJavaSymbols() {
    String source = """
        public class Calculator {
          private int base;
          public int add(int left, int right) {
            return left + right + base;
          }
          public void reset() {
            base = 0;
          }
        }
        """.stripTrailing();

    List<CodeChunk> chunks = chunker.chunk("Calculator.java", "Java", source);

    assertThat(chunks).anySatisfy(chunk -> {
      assertThat(chunk.symbolName()).isEqualTo("Calculator");
      assertThat(chunk.symbolKind()).isEqualTo("CLASS");
      assertThat(chunk.startLine()).isEqualTo(1);
      assertThat(chunk.endLine()).isEqualTo(9);
    });
    assertThat(chunks).anySatisfy(chunk -> {
      assertThat(chunk.symbolName()).isEqualTo("add");
      assertThat(chunk.startLine()).isEqualTo(3);
      assertThat(chunk.endLine()).isEqualTo(5);
    });
    assertThat(chunks).anySatisfy(chunk -> {
      assertThat(chunk.symbolName()).isEqualTo("reset");
      assertThat(chunk.startLine()).isEqualTo(6);
      assertThat(chunk.endLine()).isEqualTo(8);
    });
  }

  @Test
  @DisplayName("Python 按缩进函数和类边界切块")
  void shouldChunkPythonSymbols() {
    String source = """
        def parse(value):
            normalized = value.strip()
            return normalized

        class Handler:
            def run(self):
                return parse("ok")
        """.stripTrailing();

    List<CodeChunk> chunks = chunker.chunk("handler.py", "Python", source);

    assertThat(chunks).anySatisfy(chunk -> {
      assertThat(chunk.symbolName()).isEqualTo("parse");
      assertThat(chunk.startLine()).isEqualTo(1);
      assertThat(chunk.endLine()).isEqualTo(4);
    });
    assertThat(chunks).anySatisfy(chunk -> {
      assertThat(chunk.symbolName()).isEqualTo("Handler");
      assertThat(chunk.startLine()).isEqualTo(5);
      assertThat(chunk.endLine()).isEqualTo(7);
    });
  }

  @Test
  @DisplayName("JavaScript 函数使用花括号边界")
  void shouldChunkJavascriptFunction() {
    String source = """
        export function buildPlan(input) {
          if (!input) {
            return null;
          }
          return { value: input };
        }
        """.stripTrailing();

    List<CodeChunk> chunks = chunker.chunk("plan.js", "JavaScript", source);

    assertThat(chunks).anySatisfy(chunk -> {
      assertThat(chunk.symbolName()).isEqualTo("buildPlan");
      assertThat(chunk.startLine()).isEqualTo(1);
      assertThat(chunk.endLine()).isEqualTo(6);
    });
  }

  @Test
  @DisplayName("未知语言明确降级为重叠行窗口")
  void shouldFallbackToOverlappingWindows() {
    properties.setChunkMaxLines(3);
    properties.setChunkOverlapLines(1);
    chunker = new GithubCodeChunker(properties);
    String source = "one\ntwo\nthree\nfour\nfive\nsix";

    List<CodeChunk> chunks = chunker.chunk("notes.txt", "Text", source);

    assertThat(chunks).extracting(CodeChunk::startLine, CodeChunk::endLine)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1, 3),
            org.assertj.core.groups.Tuple.tuple(3, 5),
            org.assertj.core.groups.Tuple.tuple(5, 6));
  }
}
