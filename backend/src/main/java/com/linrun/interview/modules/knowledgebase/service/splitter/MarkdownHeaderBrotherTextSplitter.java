package com.linrun.interview.modules.knowledgebase.service.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import com.linrun.interview.common.id.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant.BROTHER_CHUNK_ID;
import static com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant.BROTHER_CHUNK_INDEX;
import static com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant.BROTHER_CHUNK_TOTAL;
import static com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant.CHUNK_ID;
import static com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant.HEADER_LEVEL;
import static com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant.PARENT_CHUNK_ID;

/**
 * Markdown 文档分割器（兄弟切片），基于标题层级分段。
 *
 * <p>copy 自 know-engine 的 MarkdownHeaderBrotherTextSplitter（@author andyflury / Hollis），
 * 适配本项目包名，移除调试 println。与父子切片的区别：超出 chunkSize 的章节按字符切割成同组兄弟 chunk，
 * 共享 brotherChunkId，并通过 brotherChunkIndex/brotherChunkTotal 记录顺序与总数，检索时可按序拼接。
 * 同时在 parentChildModel 模式下为非顶级标题建立 parentChunkId 父子关系。
 */
@Slf4j
public class MarkdownHeaderBrotherTextSplitter implements DocumentSplitter {

    private static final Map<String, String> DEFAULT_HEADERS_TO_SPLIT = new HashMap<>();

    static {
        DEFAULT_HEADERS_TO_SPLIT.put("#", "title");
        DEFAULT_HEADERS_TO_SPLIT.put("##", "subtitle");
        DEFAULT_HEADERS_TO_SPLIT.put("###", "subsubtitle");
        DEFAULT_HEADERS_TO_SPLIT.put("####", "subsubsubtitle");
        DEFAULT_HEADERS_TO_SPLIT.put("#####", "subsubsubsubtitle");
        DEFAULT_HEADERS_TO_SPLIT.put("######", "subsubsubsubsubtitle");
    }

    private final List<Map.Entry<String, String>> headersToSplitOn;
    private final boolean returnEachLine;
    private final boolean stripHeaders;
    private final boolean parentChildModel;
    private final int chunkSize;
    private final int overlap;

    public MarkdownHeaderBrotherTextSplitter(Map<String, String> headersToSplitOn, boolean returnEachLine,
                                             boolean stripHeaders, boolean parentChildModel) {
        this(headersToSplitOn, returnEachLine, stripHeaders, parentChildModel, 0, 0);
    }

    public MarkdownHeaderBrotherTextSplitter(int chunkSize, int overlap) {
        this(DEFAULT_HEADERS_TO_SPLIT, true, false, true, chunkSize, overlap);
    }

    public MarkdownHeaderBrotherTextSplitter(Map<String, String> headersToSplitOn, boolean returnEachLine,
                                             boolean stripHeaders, boolean parentChildModel, int chunkSize, int overlap) {
        this.headersToSplitOn = headersToSplitOn.entrySet().stream()
            .sorted(Comparator.comparingInt(e -> -e.getKey().length()))
            .collect(Collectors.toList());
        this.returnEachLine = returnEachLine;
        this.stripHeaders = stripHeaders;
        this.parentChildModel = parentChildModel;
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    @Override
    public List<TextSegment> split(Document document) {
        String text = java.util.Arrays.stream(document.text().split("\n"))
            .filter(line -> !line.trim().isEmpty())
            .collect(Collectors.joining("\n"));

        List<TextSegment> result = new ArrayList<>();
        List<DocumentWithMetadata> segments = splitWithMetadata(text, document.metadata().toMap());
        for (DocumentWithMetadata segment : segments) {
            result.add(new TextSegment(segment.getContent(), Metadata.from(segment.getMetadata())));
        }
        log.debug("[MarkdownHeaderBrotherTextSplitter] 分割完成: segments={}", segments.size());
        return result;
    }

    public List<TextSegment> splitText(String text) {
        String filteredText = java.util.Arrays.stream(text.split("\n"))
            .filter(line -> !line.trim().isEmpty())
            .collect(Collectors.joining("\n"));

        List<TextSegment> result = new ArrayList<>();
        List<DocumentWithMetadata> segments = splitWithMetadata(filteredText, new HashMap<>());
        for (DocumentWithMetadata segment : segments) {
            result.add(new TextSegment(segment.getContent(), Metadata.from(segment.getMetadata())));
        }
        return result;
    }

    private List<DocumentWithMetadata> splitWithMetadata(String text, Map<String, Object> baseMetadata) {
        List<String> lines = java.util.Arrays.asList(text.split("\n"));
        List<Line> linesWithMetadata = new ArrayList<>();
        List<String> currentContent = new ArrayList<>();
        Map<String, Object> currentMetadata = new HashMap<>(baseMetadata);
        List<Header> headerStack = new ArrayList<>();
        Map<String, Object> initialMetadata = new HashMap<>(baseMetadata);

        boolean inCodeBlock = false;
        String openingFence = "";

        for (String line : lines) {
            String strippedLine = line.trim();

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

            if (inCodeBlock) {
                currentContent.add(strippedLine);
                continue;
            }

            interrupted:
            {
                for (Map.Entry<String, String> header : headersToSplitOn) {
                    String sep = header.getKey();
                    String name = header.getValue();

                    if (strippedLine.startsWith(sep) && (strippedLine.length() == sep.length() || strippedLine.charAt(sep.length()) == ' ')) {
                        if (name != null) {
                            int currentHeaderLevel = (int) sep.chars().filter(ch -> ch == '#').count();

                            while (!headerStack.isEmpty() && headerStack.get(headerStack.size() - 1).getLevel() >= currentHeaderLevel) {
                                Header poppedHeader = headerStack.remove(headerStack.size() - 1);
                                initialMetadata.remove(poppedHeader.getName());
                            }

                            Header headerType = new Header(currentHeaderLevel, name, strippedLine.substring(sep.length()).trim());
                            headerStack.add(headerType);
                            initialMetadata.put(name, headerType.getData());
                            initialMetadata.put(HEADER_LEVEL, currentHeaderLevel);
                            String currentChunkId = SnowflakeIdGenerator.getInstance().nextIdStr();
                            initialMetadata.put(CHUNK_ID, currentChunkId);
                        }

                        if (!currentContent.isEmpty()) {
                            linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
                            currentContent.clear();
                        }

                        if (!stripHeaders) {
                            currentContent.add(strippedLine);
                        }

                        break interrupted;
                    }
                }

                if (!strippedLine.isEmpty()) {
                    currentContent.add(strippedLine);
                } else if (!currentContent.isEmpty()) {
                    linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
                    currentContent.clear();
                }
            }

            currentMetadata = new HashMap<>(initialMetadata);
        }

        if (!currentContent.isEmpty()) {
            linesWithMetadata.add(new Line(String.join("\n", currentContent), currentMetadata));
        }

        List<DocumentWithMetadata> segments;
        if (!returnEachLine) {
            segments = aggregateLinesToChunks(linesWithMetadata);
        } else {
            segments = linesWithMetadata.stream()
                .map(line -> new DocumentWithMetadata(line.getContent(), line.getMetadata()))
                .collect(Collectors.toList());
        }

        if (chunkSize > 0) {
            segments = splitByChunkSize(segments);
        }

        return segments;
    }

    private List<DocumentWithMetadata> aggregateLinesToChunks(List<Line> lines) {
        List<Line> aggregatedChunks = new ArrayList<>();
        for (Line line : lines) {
            if (!aggregatedChunks.isEmpty() && aggregatedChunks.get(aggregatedChunks.size() - 1).getMetadata().equals(line.getMetadata())) {
                Line last = aggregatedChunks.get(aggregatedChunks.size() - 1);
                last.setContent(last.getContent() + "  \n" + line.getContent());
            } else if (!aggregatedChunks.isEmpty() && !aggregatedChunks.get(aggregatedChunks.size() - 1).getMetadata().equals(line.getMetadata())
                && aggregatedChunks.get(aggregatedChunks.size() - 1).getMetadata().size() < line.getMetadata().size()
                && aggregatedChunks.get(aggregatedChunks.size() - 1).getContent().split("\n")[aggregatedChunks.get(aggregatedChunks.size() - 1).getContent().split("\n").length - 1].startsWith("#") && !stripHeaders) {

                Line last = aggregatedChunks.get(aggregatedChunks.size() - 1);
                last.setContent(last.getContent() + "  \n" + line.getContent());
            } else {
                aggregatedChunks.add(line);
            }
        }

        if (parentChildModel) {
            try {
                for (int i = 0; i < aggregatedChunks.size(); i++) {
                    Map<String, Object> currentMetaData = aggregatedChunks.get(i).getMetadata();
                    Integer headerLevel = (Integer) currentMetaData.get(HEADER_LEVEL);
                    if (headerLevel == null || headerLevel == 1) {
                        continue;
                    }
                    if (headerLevel > 1) {
                        for (int j = i - 1; j >= 0; j--) {
                            Map<String, Object> lastMetaData = aggregatedChunks.get(j).getMetadata();
                            Integer lastHeaderLevel = (Integer) lastMetaData.get(HEADER_LEVEL);
                            if (lastHeaderLevel != null && lastHeaderLevel < headerLevel) {
                                currentMetaData.put(PARENT_CHUNK_ID, lastMetaData.get(CHUNK_ID));
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[MarkdownHeaderBrotherTextSplitter] 父子模式转换失败: {}", e.getMessage());
            }
        }

        return aggregatedChunks.stream()
            .map(chunk -> new DocumentWithMetadata(chunk.getContent(), chunk.getMetadata()))
            .collect(Collectors.toList());
    }

    /**
     * 兄弟切片：超出 chunkSize 的章节按字符切割成同组兄弟 chunk，共享 brotherChunkId，
     * 并记录 brotherChunkIndex/brotherChunkTotal 供检索时按序拼接。
     */
    private List<DocumentWithMetadata> splitByChunkSize(List<DocumentWithMetadata> segments) {
        List<DocumentWithMetadata> result = new ArrayList<>();
        for (DocumentWithMetadata segment : segments) {
            String content = segment.getContent();
            if (content.length() <= chunkSize) {
                result.add(segment);
            } else {
                String brotherChunkId = SnowflakeIdGenerator.getInstance().nextIdStr();
                List<DocumentWithMetadata> subChunks = new ArrayList<>();

                int start = 0;
                while (start < content.length()) {
                    int end = Math.min(start + chunkSize, content.length());
                    String subContent = content.substring(start, end);

                    Map<String, Object> subMetadata = new HashMap<>(segment.getMetadata());
                    subMetadata.put(CHUNK_ID, SnowflakeIdGenerator.getInstance().nextIdStr());
                    subMetadata.put(BROTHER_CHUNK_ID, brotherChunkId);

                    subChunks.add(new DocumentWithMetadata(subContent, subMetadata));

                    if (end == content.length()) {
                        break;
                    }
                    start = end - Math.min(overlap, end);
                }

                int total = subChunks.size();
                for (int i = 0; i < total; i++) {
                    subChunks.get(i).getMetadata().put(BROTHER_CHUNK_INDEX, i + 1);
                    subChunks.get(i).getMetadata().put(BROTHER_CHUNK_TOTAL, total);
                }

                result.addAll(subChunks);
            }
        }
        return result;
    }

    public static class Line {
        private String content;
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

    public static class Header {
        private int level;
        private String name;
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
