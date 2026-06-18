package interview.guide.modules.knowledgebase.service.chunk;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语义分片：按句末标点（。！？.!?）切成完整句子，再贪心累积到 chunkSize 字符成块。
 * 保证句子不被切断，适合问答对、短段落为主的知识库；文档短于 chunkSize 时退化为单块。
 */
public class SemanticChunkStrategy implements ChunkStrategy {

    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("([。！？!?\\.])");

    private final int chunkSize;

    public SemanticChunkStrategy(int chunkSize) {
        this.chunkSize = Math.max(64, chunkSize);
    }

    @Override
    public String name() {
        return "semantic";
    }

    @Override
    public List<Document> split(String content, int overlapChars) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> sentences = splitSentences(content);
        List<Document> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (sentence == null || sentence.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && current.length() + sentence.length() > chunkSize) {
                chunks.add(new Document(current.toString()));
                current.setLength(0);
            }
            current.append(sentence);
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

    private List<String> splitSentences(String content) {
        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE_BOUNDARY.matcher(content);
        int last = 0;
        while (matcher.find()) {
            int end = matcher.end();
            if (end > last) {
                sentences.add(content.substring(last, end));
            }
            last = end;
        }
        if (last < content.length()) {
            sentences.add(content.substring(last));
        }
        return sentences;
    }
}
