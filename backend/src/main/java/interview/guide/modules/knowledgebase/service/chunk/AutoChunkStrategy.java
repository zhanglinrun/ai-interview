package interview.guide.modules.knowledgebase.service.chunk;

import org.springframework.ai.document.Document;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 自动选择策略：根据文档结构特征挑最合适的底层策略。
 * <ul>
 *   <li>标题数 ≥ 2 → hybrid（保留章节结构，便于按章节检索）</li>
 *   <li>无标题且篇幅较长（&gt; chunkSize × 2）→ recursive（按分隔符层级切，减少硬切断）</li>
 *   <li>其余（短文本、问答对为主）→ semantic（按完整句子累积，保证句义完整）</li>
 * </ul>
 */
public class AutoChunkStrategy implements ChunkStrategy {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern PLAIN_HEADING = Pattern.compile(
        "^(第[一二三四五六七八九十百千万0-9]+[章节部分篇]|[一二三四五六七八九十]+、|\\d+(\\.\\d+)*[、.]\\s*)\\S.{0,80}$"
    );
    private static final int MIN_HEADING_FOR_HYBRID = 2;

    private final ChunkStrategy recursive;
    private final ChunkStrategy semantic;
    private final ChunkStrategy hybrid;
    private final int chunkSize;

    public AutoChunkStrategy(ChunkStrategy recursive, ChunkStrategy semantic, ChunkStrategy hybrid, int chunkSize) {
        this.recursive = recursive;
        this.semantic = semantic;
        this.hybrid = hybrid;
        this.chunkSize = Math.max(64, chunkSize);
    }

    @Override
    public String name() {
        return "auto";
    }

    @Override
    public List<Document> split(String content, int overlapChars) {
        return choose(content).split(content, overlapChars);
    }

    /** 暴露自动选定的底层策略名，便于观测与测试。 */
    public String chosenStrategy(String content) {
        return choose(content).name();
    }

    private ChunkStrategy choose(String content) {
        if (countHeadings(content) >= MIN_HEADING_FOR_HYBRID) {
            return hybrid;
        }
        if (content.length() > chunkSize * 2) {
            return recursive;
        }
        return semantic;
    }

    private int countHeadings(String content) {
        String normalized = content == null ? "" : content.replace("\r\n", "\n").replace('\r', '\n');
        int count = 0;
        for (String line : normalized.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            if (MARKDOWN_HEADING.matcher(trimmed).matches() || PLAIN_HEADING.matcher(trimmed).matches()) {
                count++;
            }
        }
        return count;
    }
}
