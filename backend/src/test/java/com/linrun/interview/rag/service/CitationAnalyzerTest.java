package com.linrun.interview.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("引用溯源分析器测试")
class CitationAnalyzerTest {

    // coverageWeight=0.5, invalidPenalty=0.1，与默认配置一致
    private final CitationAnalyzer analyzer = new CitationAnalyzer(0.5, 0.1);

    @Test
    @DisplayName("有效引用应被识别，覆盖率按不同来源数计算")
    void shouldCollectValidCitations() {
        CitationAnalyzer.CitationAnalysis result =
            analyzer.analyze("Redis 支持持久化 [1]，包括 RDB 和 AOF [1][2]。", 3);

        assertThat(result.citedIndexes()).containsExactlyInAnyOrder(1, 2);
        assertThat(result.invalidIndexes()).isEmpty();
        // 2 个不同有效来源 / 3 个来源
        assertThat(result.coverage()).isEqualTo(2.0 / 3.0, within(1e-9));
    }

    @Test
    @DisplayName("超出来源范围的编号应判为编造引用")
    void shouldFlagOutOfRangeCitationsAsInvalid() {
        CitationAnalyzer.CitationAnalysis result = analyzer.analyze("某说法见 [5]，另见 [9]。", 3);

        assertThat(result.citedIndexes()).isEmpty();
        assertThat(result.invalidIndexes()).containsExactlyInAnyOrder(5, 9);
        assertThat(result.coverage()).isZero();
    }

    @Test
    @DisplayName("有效与编造编号混合时应分别归集")
    void shouldSeparateValidAndInvalid() {
        CitationAnalyzer.CitationAnalysis result = analyzer.analyze("基础见 [1]，扩展见 [7]。", 3);

        assertThat(result.citedIndexes()).containsExactly(1);
        assertThat(result.invalidIndexes()).containsExactly(7);
    }

    @Test
    @DisplayName("空回答、无编号或无来源时应返回空引用、覆盖率 0")
    void shouldReturnEmptyForBlankOrNoSource() {
        assertThat(analyzer.analyze("", 3).citedIndexes()).isEmpty();
        assertThat(analyzer.analyze("没有任何编号的回答", 3).coverage()).isZero();
        assertThat(analyzer.analyze("见 [1]", 0).citedIndexes()).isEmpty();
    }

    @Test
    @DisplayName("置信度应综合平均相似度与覆盖率：高覆盖+高相似=高置信")
    void shouldComputeConfidenceFromSimilarityAndCoverage() {
        // 3 个来源全部被引用：coverage=1.0，平均相似度 0.8
        // confidence = 0.8*0.5 + 1.0*0.5 - 0 = 0.9
        CitationAnalyzer.CitationAnalysis fullCoverage = analyzer.analyze("见 [1][2][3]", 3);
        assertThat(analyzer.confidence(List.of(0.8, 0.8, 0.8), fullCoverage))
            .isEqualTo(0.9, within(1e-9));

        // 没有任何引用：coverage=0
        // confidence = 0.8*0.5 + 0 = 0.4
        CitationAnalyzer.CitationAnalysis noCite = analyzer.analyze("无编号回答", 3);
        assertThat(analyzer.confidence(List.of(0.8, 0.8, 0.8), noCite))
            .isEqualTo(0.4, within(1e-9));
    }

    @Test
    @DisplayName("编造引用应按配置扣分压低置信度")
    void shouldPenalizeInvalidCitations() {
        // 有效 1 个、编造 1 个、coverage=1/3
        // confidence = 0.8*0.5 + (1/3)*0.5 - 0.1 = 0.4 + 0.16667 - 0.1 = 0.4667
        CitationAnalyzer.CitationAnalysis mixed = analyzer.analyze("见 [1] 和 [7]", 3);
        assertThat(analyzer.confidence(List.of(0.8, 0.8, 0.8), mixed))
            .isEqualTo(0.4667, within(1e-3));
    }
}
