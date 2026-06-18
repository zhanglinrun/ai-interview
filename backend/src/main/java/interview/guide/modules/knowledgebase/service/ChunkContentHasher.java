package interview.guide.modules.knowledgebase.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * chunk 内容哈希：用于 chunk 级去重与增量更新。
 *
 * <p>用 SHA-256 计算每段 chunk 文本的哈希，作为该 chunk 的稳定内容标识：
 * <ul>
 *   <li>同文档内出现重复内容时，按 hash 去重，只保留首个；</li>
 *   <li>增量更新时，对比已入库 chunk 的 hash，内容未变的 chunk 复用旧向量、跳过 embedding。</li>
 * </ul>
 */
public final class ChunkContentHasher {

    private ChunkContentHasher() {
    }

    /**
     * 计算文本的 SHA-256 十六进制哈希。null 或空文本返回空串（调用方据此判空）。
     */
    public static String hash(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
