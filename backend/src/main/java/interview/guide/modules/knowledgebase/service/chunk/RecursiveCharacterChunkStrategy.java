package interview.guide.modules.knowledgebase.service.chunk;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 递归字符分片：按分隔符层级（段落 → 换行 → 句末标点 → 分号 → 逗号 → 空格）逐级切分，
 * 直到每块不超过 chunkSize 字符；再贪心合并相邻小片段。思路对齐 LangChain 的
 * RecursiveCharacterTextSplitter，尽量在自然边界断开，避免硬切断句子。
 */
public class RecursiveCharacterChunkStrategy implements ChunkStrategy {

    private static final List<String> SEPARATORS = List.of(
            "\n\n", "\n", "。", "！", "？", "；", ";", "，", ",", " ", "");

    private final int chunkSize;

    public RecursiveCharacterChunkStrategy(int chunkSize) {
        this.chunkSize = Math.max(64, chunkSize);
    }

    @Override
    public String name() {
        return "recursive";
    }

    @Override
    public List<Document> split(String content, int overlapChars) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> pieces = splitRecursively(content, 0);
        List<Document> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            if (piece == null || piece.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && current.length() + piece.length() > chunkSize) {
                chunks.add(new Document(current.toString()));
                current.setLength(0);
            }
            current.append(piece);
            // 单片段已是最细粒度仍超长：硬切兜底，保证不会出现超过 chunkSize 太多的块
            while (current.length() > chunkSize) {
                chunks.add(new Document(current.substring(0, chunkSize)));
                current.delete(0, chunkSize);
            }
        }
        if (current.length() > 0) {
            chunks.add(new Document(current.toString()));
        }
        return ChunkOverlap.apply(chunks, overlapChars);
    }

    private List<String> splitRecursively(String text, int separatorIndex) {
        if (text.length() <= chunkSize) {
            return text.isEmpty() ? List.of() : List.of(text);
        }
        if (separatorIndex >= SEPARATORS.size()) {
            return List.of(text);
        }
        String separator = SEPARATORS.get(separatorIndex);
        String[] parts = separator.isEmpty()
                ? new String[]{text}
                : text.split(Pattern.quote(separator), -1);
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            // 把分隔符补回片段尾部，避免相邻片段拼接时丢字
            String piece = separator.isEmpty() ? part : part + separator;
            if (piece.length() <= chunkSize) {
                result.add(piece);
            } else {
                result.addAll(splitRecursively(piece, separatorIndex + 1));
            }
        }
        return result;
    }
}
