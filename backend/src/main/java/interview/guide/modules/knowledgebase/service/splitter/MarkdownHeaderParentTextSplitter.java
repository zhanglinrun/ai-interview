package interview.guide.modules.knowledgebase.service.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import interview.guide.common.id.SnowflakeIdGenerator;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static interview.guide.modules.knowledgebase.constant.MetadataKeyConstant.CHUNK_ID;
import static interview.guide.modules.knowledgebase.constant.MetadataKeyConstant.HEADER_LEVEL;
import static interview.guide.modules.knowledgebase.constant.MetadataKeyConstant.PARENT_CHUNK_ID;
import static interview.guide.modules.knowledgebase.constant.MetadataKeyConstant.SKIP_EMBEDDING;

/**
 * Markdown 文档分割器（父子切片），基于标题层级分段。
 *
 * <p>copy 自 know-engine 的 MarkdownHeaderParentTextSplitter（@author andyflury / Hollis），
 * 适配本项目包名：MetadataKeyConstant/SnowflakeIdGenerator 指向本项目实现，移除调试 println。
 *
 * <p>父子切片语义：超出 chunkSize 的章节保留完整内容作为父 chunk（标记 skipEmbedding 不做向量），
 * 同时按 chunkSize 二次切割出多个子 chunk，子 chunk 通过 parentChunkId 指向父 chunk。
 * 检索时子 chunk 精准命中，再聚合父 chunk 获得完整上下文。
 */
@Slf4j
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

    private final List<Map.Entry<String, String>> headersToSplitOn;
    private final boolean returnEachLine;
    private final boolean stripHeaders;
    private final int chunkSize;
    private final int overlap;

    public MarkdownHeaderParentTextSplitter(Map<String, String> headersToSplitOn,
                                            boolean returnEachLine, boolean stripHeaders) {
        this(headersToSplitOn, returnEachLine, stripHeaders, 0, 0);
    }

    public MarkdownHeaderParentTextSplitter(int chunkSize, int overlap) {
        this(DEFAULT_HEADERS_TO_SPLIT, true, false, chunkSize, overlap);
    }

    public MarkdownHeaderParentTextSplitter(int titleLevel, boolean returnEachLine,
                                            boolean stripHeaders, int chunkSize, int overlap) {
        this(buildHeadersMap(titleLevel), returnEachLine, stripHeaders, chunkSize, overlap);
    }

    private static Map<String, String> buildHeadersMap(int titleLevel) {
        if (titleLevel < 1 || titleLevel > 6) {
            throw new IllegalArgumentException("titleLevel must be between 1 and 6, but got: " + titleLevel);
        }
        String[] names = {"title", "subtitle", "subsubtitle", "subsubsubtitle", "subsubsubsubtitle", "subsubsubsubsubtitle"};
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i <= titleLevel; i++) {
            headers.put("#".repeat(i), names[i - 1]);
        }
        return headers;
    }

    public MarkdownHeaderParentTextSplitter(Map<String, String> headersToSplitOn, boolean returnEachLine,
                                            boolean stripHeaders, int chunkSize, int overlap) {
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
        String text = java.util.Arrays.stream(document.text().split("\n"))
            .filter(line -> !line.trim().isEmpty())
            .collect(Collectors.joining("\n"));

        List<TextSegment> result = new ArrayList<>();
        List<DocumentWithMetadata> segments = splitWithMetadata(text, document.metadata().toMap());
        for (DocumentWithMetadata segment : segments) {
            result.add(new TextSegment(segment.getContent(), Metadata.from(segment.getMetadata())));
        }
        log.debug("[MarkdownHeaderParentTextSplitter] 分割完成: segments={}", segments.size());
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

        return aggregatedChunks.stream()
            .map(chunk -> new DocumentWithMetadata(chunk.getContent(), chunk.getMetadata()))
            .collect(Collectors.toList());
    }

    /**
     * 父子切片：超出 chunkSize 的章节保留完整父 chunk（skipEmbedding=1 不做向量），
     * 同时二次切割出多个子 chunk，子 chunk 通过 parentChunkId 指向父 chunk。
     */
    private List<DocumentWithMetadata> splitByChunkSize(List<DocumentWithMetadata> segments) {
        List<DocumentWithMetadata> result = new ArrayList<>();
        for (DocumentWithMetadata segment : segments) {
            String content = segment.getContent();
            if (content.length() <= chunkSize) {
                result.add(segment);
            } else {
                Map<String, Object> fullMetadata = new HashMap<>(segment.getMetadata());
                String parentChunkId = SnowflakeIdGenerator.getInstance().nextIdStr();
                fullMetadata.put(CHUNK_ID, parentChunkId);
                fullMetadata.put(SKIP_EMBEDDING, 1);
                result.add(new DocumentWithMetadata(content, fullMetadata));

                int start = 0;
                while (start < content.length()) {
                    int end = Math.min(start + chunkSize, content.length());
                    String subContent = content.substring(start, end);

                    Map<String, Object> subMetadata = new HashMap<>(segment.getMetadata());
                    subMetadata.put(CHUNK_ID, SnowflakeIdGenerator.getInstance().nextIdStr());
                    subMetadata.put(PARENT_CHUNK_ID, parentChunkId);

                    result.add(new DocumentWithMetadata(subContent, subMetadata));

                    if (end == content.length()) {
                        break;
                    }
                    start = end - Math.min(overlap, end);
                }
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
