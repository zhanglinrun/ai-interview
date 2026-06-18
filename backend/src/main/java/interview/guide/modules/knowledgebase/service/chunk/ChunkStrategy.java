package interview.guide.modules.knowledgebase.service.chunk;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 知识库分块策略抽象：不同策略用不同粒度切分文档，产出带 metadata 的 chunk。
 * <p>
 * overlap 由各策略在内部统一施加（相邻 chunk 末尾/开头重叠 overlapChars 字符），
 * 保证切换策略时重叠语义一致。
 */
public interface ChunkStrategy {

    /** 策略名（recursive / semantic / hybrid / auto）。 */
    String name();

    /**
     * @param content      原始文档文本（调用方保证非空）
     * @param overlapChars 相邻 chunk 重叠字符数，0 表示关闭
     * @return 分块结果（已施加 overlap）
     */
    List<Document> split(String content, int overlapChars);
}
