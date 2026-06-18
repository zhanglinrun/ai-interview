package interview.guide.modules.knowledgebase.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用溯源分析器：解析回答正文里的来源编号 [n]，校验引用真实性并算综合置信度。
 * 纯计算、无外部依赖，便于单测覆盖引用解析与置信度口径。
 */
final class CitationAnalyzer {

    /** 匹配回答正文里的来源编号 [n]。 */
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d+)]");

    private final double coverageWeight;
    private final double invalidPenalty;

    CitationAnalyzer(double coverageWeight, double invalidPenalty) {
        this.coverageWeight = coverageWeight;
        this.invalidPenalty = invalidPenalty;
    }

    /**
     * 解析回答里的 [n] 编号。落在 [1, sourceCount] 内的是有效引用，其余是编造的越界编号；
     * coverage = 被引用的不同有效来源数 / 来源总数，衡量回答对检索内容的依赖程度。
     */
    CitationAnalysis analyze(String answer, int sourceCount) {
        if (answer == null || answer.isBlank() || sourceCount <= 0) {
            return new CitationAnalysis(List.of(), List.of(), 0.0d);
        }
        Set<Integer> valid = new LinkedHashSet<>();
        Set<Integer> invalid = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            try {
                int n = Integer.parseInt(matcher.group(1));
                if (n >= 1 && n <= sourceCount) {
                    valid.add(n);
                } else {
                    invalid.add(n);
                }
            } catch (NumberFormatException ignored) {
                // 正则已限定为数字，理论上不会进入
            }
        }
        double coverage = (double) valid.size() / sourceCount;
        return new CitationAnalysis(new ArrayList<>(valid), new ArrayList<>(invalid), coverage);
    }

    /**
     * 综合置信度：平均相关性分与引用覆盖率的加权，再按编造引用数量扣分，截断到 0~1。
     * 相关性分反映检索质量，覆盖率反映回答对检索内容的真实依赖，两者都高才高置信。
     *
     * @param similarities 各来源的相关性分（已归一化到 0~1，可为 null 表示缺失）
     */
    double confidence(List<Double> similarities, CitationAnalysis analysis) {
        double avgSimilarity = similarities == null || similarities.isEmpty()
            ? 0.0d
            : similarities.stream()
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0d);
        double confidence = avgSimilarity * (1.0d - coverageWeight)
            + analysis.coverage() * coverageWeight
            - analysis.invalidIndexes().size() * invalidPenalty;
        return Math.max(0.0d, Math.min(1.0d, Math.round(confidence * 10000.0d) / 10000.0d));
    }

    /** 引用溯源分析结果：被实际引用的有效来源编号、编造的越界编号、引用覆盖率。 */
    record CitationAnalysis(
        List<Integer> citedIndexes,
        List<Integer> invalidIndexes,
        double coverage
    ) {
    }
}
