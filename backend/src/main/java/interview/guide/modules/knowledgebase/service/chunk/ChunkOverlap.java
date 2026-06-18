package interview.guide.modules.knowledgebase.service.chunk;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * 通用 chunk 重叠工具：下一个 chunk 开头拼上一个 chunk 的末尾 overlapChars 字符，
 * 降低边界硬切断导致的上下文丢失。所有策略共用，保证重叠语义一致。
 */
public final class ChunkOverlap {

    private ChunkOverlap() {
    }

    public static List<Document> apply(List<Document> chunks, int overlapChars) {
        if (overlapChars <= 0 || chunks == null || chunks.size() < 2) {
            return chunks;
        }
        List<Document> result = new ArrayList<>(chunks.size());
        result.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            String prevText = chunks.get(i - 1).getText();
            String curText = chunks.get(i).getText();
            String tail = (prevText == null || prevText.length() <= overlapChars)
                    ? (prevText == null ? "" : prevText)
                    : prevText.substring(prevText.length() - overlapChars);
            result.add(new Document(
                    tail + (curText == null ? "" : curText),
                    chunks.get(i).getMetadata()));
        }
        return result;
    }
}
