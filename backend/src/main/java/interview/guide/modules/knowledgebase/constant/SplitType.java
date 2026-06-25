package interview.guide.modules.knowledgebase.constant;

/**
 * 知识库切分类型（对齐 know-engine SplitType）。
 *
 * <p>ai-interview 当前主用 {@link #TITLE}（MarkdownHeaderBrotherTextSplitter），
 * 其余类型保留以对齐 know-engine 的 DocumentSplitterFactory 选择能力。
 */
public enum SplitType {
    /** 按长度切分。 */
    LENGTH,
    /** 按标题层级切分（MarkdownHeaderParent/BrotherTextSplitter）。 */
    TITLE,
    /** 按正则切分。 */
    REGEX,
    /** 智能切分（默认走标题层级）。 */
    SMART,
    /** 按分隔符切分。 */
    SEPARATOR
}
