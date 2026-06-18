package interview.guide.modules.knowledgebase.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Small-to-big 上下文扩展：把命中 chunk 的同段兄弟文本聚合成更大上下文。
 * 纯逻辑，便于单测；与向量查询解耦。
 */
final class ParentContextExpander {

    private ParentContextExpander() {
    }

    /**
     * @param baseText 命中的小 chunk 文本（始终保留在最前）
     * @param siblings 同段兄弟 chunk 文本（按顺序聚合）
     * @param maxChars 聚合后总字符上限，超出则停止追加
     * @return 扩展后的上下文文本
     */
    static String expand(String baseText, List<String> siblings, int maxChars) {
        String base = baseText == null ? "" : baseText;
        StringBuilder sb = new StringBuilder(base);
        Set<String> seen = new LinkedHashSet<>();
        seen.add(base.trim());
        if (siblings == null) {
            return sb.toString();
        }
        for (String sibling : siblings) {
            if (sibling == null || sibling.isBlank()) {
                continue;
            }
            String trimmed = sibling.trim();
            if (!seen.add(trimmed)) {
                continue;
            }
            if (sb.length() + trimmed.length() + 2 > maxChars) {
                break;
            }
            sb.append("\n\n").append(trimmed);
        }
        return sb.toString();
    }
}
