package interview.guide.modules.knowledgebase.service.chunk;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 混合分片（默认策略）：优先保留 Markdown/普通标题结构，再对每个章节做 token 分片；
 * 没有明显标题结构时退回整篇 token 分片。章节内的相邻 chunk 施加 overlap。
 */
public class HybridHeadingChunkStrategy implements ChunkStrategy {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern PLAIN_HEADING = Pattern.compile(
        "^(第[一二三四五六七八九十百千万0-9]+[章节部分篇]|[一二三四五六七八九十]+、|\\d+(\\.\\d+)*[、.]\\s*)\\S.{0,80}$"
    );
    private static final int MIN_HEADING_COUNT = 2;

    private final TextSplitter tokenTextSplitter;

    public HybridHeadingChunkStrategy() {
        this(TokenTextSplitter.builder().build());
    }

    public HybridHeadingChunkStrategy(TextSplitter tokenTextSplitter) {
        this.tokenTextSplitter = tokenTextSplitter;
    }

    @Override
    public String name() {
        return "hybrid";
    }

    @Override
    public List<Document> split(String content, int overlapChars) {
        List<Section> sections = splitByHeadings(content);
        if (sections.size() < MIN_HEADING_COUNT) {
            return ChunkOverlap.apply(tokenTextSplitter.apply(List.of(new Document(content))), overlapChars);
        }

        List<Document> chunks = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            Document sectionDocument = new Document(
                section.text(),
                Map.of(
                    "section_title", section.title(),
                    "section_level", String.valueOf(section.level()),
                    "section_index", String.valueOf(i)
                )
            );
            List<Document> sectionChunks = tokenTextSplitter.apply(List.of(sectionDocument));
            if (sectionChunks.isEmpty()) {
                continue;
            }
            sectionChunks = ChunkOverlap.apply(sectionChunks, overlapChars);
            for (Document chunk : sectionChunks) {
                chunk.getMetadata().put("section_title", section.title());
                chunk.getMetadata().put("section_level", String.valueOf(section.level()));
                chunk.getMetadata().put("section_index", String.valueOf(i));
            }
            chunks.addAll(sectionChunks);
        }
        return chunks;
    }

    private List<Section> splitByHeadings(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<Section> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentTitle = "";
        int currentLevel = 0;
        int headingCount = 0;

        for (String line : lines) {
            Heading heading = parseHeading(line);
            if (heading != null) {
                headingCount++;
                if (!current.isEmpty()) {
                    sections.add(new Section(
                        currentTitle.isBlank() ? "前言" : currentTitle,
                        currentLevel,
                        current.toString().trim()
                    ));
                    current.setLength(0);
                }
                currentTitle = heading.title();
                currentLevel = heading.level();
            }
            current.append(line).append('\n');
        }

        if (!current.isEmpty()) {
            sections.add(new Section(
                currentTitle.isBlank() ? "正文" : currentTitle,
                currentLevel,
                current.toString().trim()
            ));
        }

        return headingCount >= MIN_HEADING_COUNT ? sections : List.of();
    }

    private Heading parseHeading(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isBlank()) {
            return null;
        }

        Matcher markdown = MARKDOWN_HEADING.matcher(trimmed);
        if (markdown.matches()) {
            return new Heading(markdown.group(2).trim(), markdown.group(1).length());
        }

        if (PLAIN_HEADING.matcher(trimmed).matches()) {
            return new Heading(trimmed, 1);
        }
        return null;
    }

    private record Heading(String title, int level) {
    }

    private record Section(String title, int level, String text) {
    }
}
