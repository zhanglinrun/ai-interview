package interview.guide.modules.knowledgebase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("父子 chunk small-to-big 上下文扩展测试")
class ParentContextExpanderTest {

    @Test
    @DisplayName("兄弟文本按顺序追加到命中 chunk 之后")
    void shouldAppendSiblingsAfterBase() {
        String result = ParentContextExpander.expand("命中段", List.of("兄弟1", "兄弟2"), 1000);
        assertThat(result).isEqualTo("命中段\n\n兄弟1\n\n兄弟2");
    }

    @Test
    @DisplayName("与命中 chunk 重复的兄弟文本被去重")
    void shouldDeduplicateBaseText() {
        String result = ParentContextExpander.expand("命中段", List.of("命中段", "兄弟1"), 1000);
        assertThat(result).isEqualTo("命中段\n\n兄弟1");
    }

    @Test
    @DisplayName("超过 maxChars 上限后停止追加")
    void shouldStopAtMaxChars() {
        // base(4) + "\n\n"(2) + "AAAAAA"(6) = 12，刚好不超；下一条会超限，停止
        String result = ParentContextExpander.expand("base", List.of("AAAAAA", "BBBBBB"), 12);
        assertThat(result).isEqualTo("base\n\nAAAAAA");
    }

    @Test
    @DisplayName("无兄弟文本时原样返回命中 chunk")
    void shouldReturnBaseWhenNoSiblings() {
        assertThat(ParentContextExpander.expand("命中段", List.of(), 1000)).isEqualTo("命中段");
        assertThat(ParentContextExpander.expand("命中段", null, 1000)).isEqualTo("命中段");
    }
}
