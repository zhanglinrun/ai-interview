package com.linrun.interview.document.service.impl;
import com.linrun.interview.rag.constant.MetadataKeyConstant;


import com.linrun.interview.infra.snowflake.SnowflakeIdGenerator;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.*;
import java.util.stream.Collectors;

import static com.linrun.interview.rag.constant.MetadataKeyConstant.*;

/**
 * Markdown 文档分割器（父子切片），基于标题层级分段。
 * 支持保留元数据与 parentChunkId 父子分段关系。
 */
public class MarkdownHeaderParentTextSplitter implements DocumentSplitter {

    private static final Map<String, String> DEFAULT_HEADERS_TO_SPLIT = new HashMap<>();

    static {
        DEFAULT_HEADERS_TO_SPLIT.put("#", "title");
        DEFAULT_HEADERS_TO_SPLIT.put("##", "subtitle");
        DEFAULT_HEADERS_TO_SPLIT.put("###", "subsubtitle");
        DEFAULT_HEADERS_TO_SPLIT.put("####", "subsubsubtitle");
        DEFAULT_HEADERS_TO_SPLIT.put("#####", "subsubsubsubtitle");
        DEFAULT_HEADERS_TO_SPLIT.put("######", "subsubsubsubsubtitle");
    }

    /**
     * 需要分割的标题列表，按标题标记长度倒序排列
     */
    private List<Map.Entry<String, String>> headersToSplitOn;

    /**
     * 是否按行返回结果
     */
    private boolean returnEachLine;

    /**
     * 是否剥离标题行本身
     */
    private boolean stripHeaders;

    /**
     * 每个分片的最大字符数，0表示不限制
     */
    private int chunkSize;

    /**
     * 相邻分片之间的重叠字符数
     */
    private int overlap;

    /**
     * 构造函数
     *
     * @param headersToSplitOn 标题分割映射表，key为标题标记（如"#"、"##"），value为元数据中的键名
     * @param returnEachLine   是否按行返回结果，false时会聚合相同元数据的行
     * @param stripHeaders     是否在结果中移除标题行
     */
    public MarkdownHeaderParentTextSplitter(Map<String, String> headersToSplitOn, boolean returnEachLine, boolean stripHeaders) {
        this(headersToSplitOn, returnEachLine, stripHeaders, 0, 0);

    }

    public MarkdownHeaderParentTextSplitter(int chunkSize, int overlap) {
        this(DEFAULT_HEADERS_TO_SPLIT, true, false, chunkSize, overlap);
    }

    /**
     * 构造函数（通过标题级别指定分割层级）
     *
     * @param titleLevel     标题级别（1-6），表示按1到titleLevel级标题进行分割
     * @param returnEachLine 是否按行返回结果，false时会聚合相同元数据的行
     * @param stripHeaders   是否在结果中移除标题行
     * @param chunkSize      每个分片的最大字符数，超出则按chunkSize再次切割，0表示不限制
     * @param overlap        相邻分片之间的重叠字符数
     */
    public MarkdownHeaderParentTextSplitter(int titleLevel, boolean returnEachLine, boolean stripHeaders, int chunkSize, int overlap) {
        this(buildHeadersMap(titleLevel), returnEachLine, stripHeaders, chunkSize, overlap);
    }

    /**
     * 根据标题级别生成标题分割映射表
     *
     * @param titleLevel 标题级别（1-6）
     * @return 标题分割映射表
     */
    private static Map<String, String> buildHeadersMap(int titleLevel) {
        if (titleLevel < 1 || titleLevel > 6) {
            throw new IllegalArgumentException("titleLevel must be between 1 and 6, but got: " + titleLevel);
        }
        String[] names = {"title", "subtitle", "subsubtitle", "subsubsubtitle", "subsubsubsubtitle", "subsubsubsubsubtitle"};
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i <= titleLevel; i++) {
            String key = "#".repeat(i);
            headers.put(key, names[i - 1]);
        }
        return headers;
    }

    /**
     * 构造函数（支持 chunkSize 和 overlap）
     *
     * @param headersToSplitOn 标题分割映射表，key为标题标记（如"#"、"##"），value为元数据中的键名
     * @param returnEachLine   是否按行返回结果，false时会聚合相同元数据的行
     * @param stripHeaders     是否在结果中移除标题行
     * @param chunkSize        每个分片的最大字符数，超出则按chunkSize再次切割，0表示不限制
     * @param overlap          相邻分片之间的重叠字符数
     */
    public MarkdownHeaderParentTextSplitter(Map<String, String> headersToSplitOn, boolean returnEachLine, boolean stripHeaders, int chunkSize, int overlap) {
        // 按标题标记长度倒序排列，确保优先匹配更长的标记（如"###"优先于"##"）
        this.headersToSplitOn = headersToSplitOn.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> -e.getKey().length()))
                .collect(Collectors.toList());
        this.returnEachLine = returnEachLine;
        this.stripHeaders = stripHeaders;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }



    @Override
    public List<TextSegment> split(Document document) {
        String text = normalizeNewlines(document.text());
        List<TextSegment> result = new ArrayList<>();
        List<DocumentWithMetadata> segments = splitWithMetadata(text, document.metadata().toMap());
        for (DocumentWithMetadata segment : segments) {
            result.add(new TextSegment(segment.getContent(), Metadata.from(segment.getMetadata())));
        }
        return result;
    }

    /**
     * 简化版分割方法，不保留元数据
     *
     * @param text 待分割的文本
     * @return 分割后的文本片段列表
     */

    public List<TextSegment> splitText(String text) {
        String filteredText = normalizeNewlines(text);
        List<TextSegment> result = new ArrayList<>();
        List<DocumentWithMetadata> segments = splitWithMetadata(filteredText, new HashMap<>());
        for (DocumentWithMetadata segment : segments) {
            result.add(new TextSegment(segment.getContent(), Metadata.from(segment.getMetadata())));
        }

        return result;
    }

    /**
     * 核心分割逻辑，保留元数据
     *
     * @param text         待分割的文本
     * @param baseMetadata 基础元数据，会被传递到每个分段中
     * @return 带有元数据的文档片段列表
     */
    private List<DocumentWithMetadata> splitWithMetadata(String text, Map<String, Object> baseMetadata) {
        List<String> lines = Arrays.asList(text.split("\n"));
        List<Line> linesWithMetadata = new ArrayList<>();
        List<String> currentContent = new ArrayList<>();
        Map<String, Object> currentMetadata = new HashMap<>(baseMetadata);
        List<Header> headerStack = new ArrayList<>();  // 标题栈，用于追踪当前的标题层级结构
        Map<String, Object> initialMetadata = new HashMap<>(baseMetadata);

        boolean inCodeBlock = false;  // 是否在代码块中
        String openingFence = "";     // 代码块的开始标记

        for (String line : lines) {
            String strippedLine = line.trim();

            // 处理代码块标记，代码块内的内容不作为标题处理
            if (!inCodeBlock) {
                if (strippedLine.startsWith("```")) {
                    inCodeBlock = !inCodeBlock;
                    openingFence = "```";
                } else if (strippedLine.startsWith("~~~")) {
                    inCodeBlock = !inCodeBlock;
                    openingFence = "~~~";
                }
            } else {
                if (strippedLine.startsWith(openingFence)) {
                    inCodeBlock = false;
                    openingFence = "";
                }
            }

            // 代码块内的内容直接添加，不做标题检测；保留缩进和空行
            if (inCodeBlock) {
                currentContent.add(line);
                continue;
            }

            // 检测并处理标题行
            interrupted:
            {
                for (Map.Entry<String, String> header : headersToSplitOn) {
                    String sep = header.getKey();    // 标题标记，如"#"、"##"
                    String name = header.getValue(); // 元数据中的键名

                    // 判断是否为有效的标题行
                    if (strippedLine.startsWith(sep) && (strippedLine.length() == sep.length() || strippedLine.charAt(sep.length()) == ' ')) {
                        if (name != null) {
                            // 计算当前标题级别（统计#的个数）
                            int currentHeaderLevel = (int) sep.chars().filter(ch -> ch == '#').count();

                            // 维护标题栈：移除所有级别大于等于当前级别的标题
                            // 这样可以正确处理标题层级关系，如从### 回退到 ##
                            while (!headerStack.isEmpty() && headerStack.get(headerStack.size() - 1).getLevel() >= currentHeaderLevel) {
                                Header poppedHeader = headerStack.remove(headerStack.size() - 1);
                                initialMetadata.remove(poppedHeader.getName());
                            }

                            // 将当前标题加入栈，并更新元数据
                            Header headerType = new Header(currentHeaderLevel, name, strippedLine.substring(sep.length()).trim());
                            headerStack.add(headerType);
                            initialMetadata.put(name, headerType.getData());
                            initialMetadata.put(HEADER_LEVEL, currentHeaderLevel);
                            // 为每个分段生成唯一ID，用于后续建立父子关系
                            String currentChunkId = SnowflakeIdGenerator.getInstance().nextIdStr();
                            initialMetadata.put(CHUNK_ID, currentChunkId);
                        }

                        // 遇到新标题时，保存之前累积的内容
                        if (!currentContent.isEmpty()) {
                            linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
                            currentContent.clear();
                        }

                        if (!stripHeaders) {
                            currentContent.add(line);
                        }

                        break interrupted;
                    }
                }

                currentContent.add(line);
            }

            // 更新当前元数据为最新的标题信息
            currentMetadata = new HashMap<>(initialMetadata);
        }

        // 处理最后累积的内容
        if (!currentContent.isEmpty()) {
            linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
        }

        // 根据配置决定返回方式
        List<DocumentWithMetadata> segments;
        if (!returnEachLine) {
            // 聚合模式：将相同元数据的行合并
            segments = aggregateLinesToChunks(linesWithMetadata);
        } else {
            // 逐行模式：保持每行独立
            segments = linesWithMetadata.stream()
                    .map(line -> new DocumentWithMetadata(line.getContent(), line.getMetadata()))
                    .collect(Collectors.toList());
        }

        segments = applyHierarchy(segments);

        // 如果设置了 chunkSize，对超出大小的分片进行二次切割
        if (chunkSize > 0) {
            segments = splitByChunkSize(segments);
        }

        return segments;
    }

    /**
     * 聚合行为分块
     * 将具有相同元数据的行合并为一个分块，并处理父子关系
     *
     * @param lines 待聚合的行列表
     * @return 聚合后的文档片段列表
     */
    private List<DocumentWithMetadata> aggregateLinesToChunks(List<Line> lines) {
        List<Line> aggregatedChunks = new ArrayList<>();
        for (Line line : lines) {
            // 情况1：元数据相同，直接合并到上一个分块
            if (!aggregatedChunks.isEmpty() && aggregatedChunks.get(aggregatedChunks.size() - 1).getMetadata().equals(line.getMetadata())) {
                Line last = aggregatedChunks.get(aggregatedChunks.size() - 1);
                last.setContent(last.getContent() + "  \n" + line.getContent());
            }
            // 情况2：元数据不同但上一行以标题结尾且未剥离标题，则也合并
            // 这样可以将标题和其下的第一段内容合并在一起
            else if (!aggregatedChunks.isEmpty() && !aggregatedChunks.get(aggregatedChunks.size() - 1).getMetadata().equals(line.getMetadata())
                    && aggregatedChunks.get(aggregatedChunks.size() - 1).getMetadata().size() < line.getMetadata().size()
                    && aggregatedChunks.get(aggregatedChunks.size() - 1).getContent().split("\n")[aggregatedChunks.get(aggregatedChunks.size() - 1).getContent().split("\n").length - 1].startsWith("#") && !stripHeaders) {

                Line last = aggregatedChunks.get(aggregatedChunks.size() - 1);
                last.setContent(last.getContent() + "  \n" + line.getContent());
            }
            // 情况3：创建新分块
            else {
                aggregatedChunks.add(line);
            }
        }

        return aggregatedChunks.stream()
                .map(chunk -> new DocumentWithMetadata(chunk.getContent(), chunk.getMetadata()))
                .collect(Collectors.toList());
    }

    /**
     * 对超出 chunkSize 的分片进行二次切割
     * <p>
     * 切割规则：
     * - 未超出 chunkSize 的分片保持不变
     * - 超出 chunkSize 的分片：保留完整分片（标记为跳过 embedding），
     *   子块先按更细标题切，再按段落 / 代码块边界滑窗
     *
     * @param segments 原始分片列表
     * @return 切割后的分片列表
     */
    private List<DocumentWithMetadata> splitByChunkSize(List<DocumentWithMetadata> segments) {
        List<DocumentWithMetadata> result = new ArrayList<>();
        for (DocumentWithMetadata segment : segments) {
            String content = segment.getContent();
            Object skip = segment.getMetadata().get(SKIP_EMBEDDING);
            if (Integer.valueOf(1).equals(skip) || "1".equals(String.valueOf(skip))) {
                result.add(segment);
                continue;
            }
            if (content.length() <= chunkSize) {
                // 未超出 chunkSize，保持原分片不变
                result.add(segment);
            } else {
                // 超出 chunkSize，需要二次切割
                // 1. 首先保留完整分片，标记为跳过embedding
                Map<String, Object> fullMetadata = new HashMap<>(segment.getMetadata());

                String parentChunkId = SnowflakeIdGenerator.getInstance().nextIdStr();

                fullMetadata.put(CHUNK_ID, parentChunkId);
                fullMetadata.put(SKIP_EMBEDDING, 1);
                result.add(new DocumentWithMetadata(content, fullMetadata));

                int childSize = ParentChildOverflowSplitter.childChunkSize(chunkSize);
                int childOverlap = Math.min(overlap, Math.max(0, childSize - 1));
                for (String subContent : ParentChildOverflowSplitter.splitChildren(content, childSize, childOverlap)) {
                    if (subContent == null || subContent.isBlank()) {
                        continue;
                    }
                    Map<String, Object> subMetadata = new HashMap<>(segment.getMetadata());
                    subMetadata.put(CHUNK_ID, SnowflakeIdGenerator.getInstance().nextIdStr());
                    subMetadata.put(PARENT_CHUNK_ID, parentChunkId);
                    result.add(new DocumentWithMetadata(subContent, subMetadata));
                }
            }
        }
        return result;
    }


    private static List<DocumentWithMetadata> applyHierarchy(List<DocumentWithMetadata> segments) {
        List<ParentChildHierarchyLinker.Section> linked = ParentChildHierarchyLinker.link(
            segments.stream()
                .map(segment -> new ParentChildHierarchyLinker.Section(segment.getContent(), segment.getMetadata()))
                .toList());
        List<DocumentWithMetadata> result = new ArrayList<>(linked.size());
        for (ParentChildHierarchyLinker.Section section : linked) {
            result.add(new DocumentWithMetadata(section.content(), section.metadata()));
        }
        return result;
    }

    private static String normalizeNewlines(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 内部类：表示带有元数据的文本行
     */
    public static class Line {
        /**
         * 文本内容
         */
        private String content;
        /**
         * 元数据信息
         */
        private Map<String, Object> metadata;

        public Line(String content, Map<String, Object> metadata) {
            this.content = content;
            this.metadata = metadata;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }
    }

    /**
     * 内部类：表示Markdown标题
     */
    public static class Header {
        /**
         * 标题级别（1-6）
         */
        private int level;
        /**
         * 元数据中的键名
         */
        private String name;
        /**
         * 标题文本内容（不含#标记）
         */
        private String data;

        public Header(int level, String name, String data) {
            this.level = level;
            this.name = name;
            this.data = data;
        }

        public int getLevel() {
            return level;
        }

        public void setLevel(int level) {
            this.level = level;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getData() {
            return data;
        }

        public void setData(String data) {
            this.data = data;
        }
    }

    /**
     * 内部类：携带元数据的文档片段
     */
    private static class DocumentWithMetadata {
        private final String content;
        private final Map<String, Object> metadata;

        public DocumentWithMetadata(String content, Map<String, Object> metadata) {
            this.content = content;
            this.metadata = new HashMap<>(metadata);
        }

        public String getContent() {
            return content;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }
    }
}
