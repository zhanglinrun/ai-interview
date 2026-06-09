package interview.guide.modules.knowledgebase.repository;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 向量存储Repository
 * 负责向量数据的增删改查操作，以及混合检索的关键词通道。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class VectorRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 关键词检索命中结果。
     *
     * @param content 文档正文
     * @param kbId    所属知识库ID（来自 metadata->>'kb_id'，可能为 null）
     * @param score   pg_trgm word_similarity 得分，范围 0~1
     */
    public record KeywordHit(String content, Long kbId, double score) {}

    /**
     * 确保 vector_store 上存在用于关键词检索的 pg_trgm GIN 索引。
     * <p>
     * vector_store 表由 Spring AI PgVectorStore 在应用启动时自动建表，
     * 早于本方法调用；init.sql 阶段该表尚不存在，因此索引必须在应用侧幂等创建。
     * 使用 IF NOT EXISTS 保证可重复执行。
     */
    public void ensureKeywordIndex() {
        try {
            // pg_trgm 扩展通常由 init.sql 创建，但它只对全新容器生效；
            // 已存在的容器或换环境时扩展可能缺失，这里幂等补建一次以自愈。
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_vector_store_content_trgm "
                    + "ON vector_store USING gin (content gin_trgm_ops)");
            log.info("[VectorRepository] 关键词检索 GIN 索引已就绪: idx_vector_store_content_trgm");
        } catch (Exception e) {
            // 索引缺失只影响关键词通道性能，不应阻断启动；混合检索会自动降级
            log.warn("[VectorRepository] 创建关键词检索 GIN 索引失败，关键词通道将退化为全表扫描: {}",
                e.getMessage());
        }
    }

    /**
     * 关键词检索通道：基于 pg_trgm word_similarity 对 content 打分。
     * <p>
     * 选择 word_similarity 而非 to_tsvector 全文检索：pgvector/pgvector:pg16 镜像
     * 不含中文分词扩展，to_tsvector('simple', ...) 无法对无空格中文正确切词，
     * 而 word_similarity 对中英文混合文本都稳定可用。
     *
     * @param query            查询文本
     * @param knowledgeBaseIds 限定的知识库ID（为空则不限定）
     * @param topK             返回候选数
     * @param minSimilarity    最低相似度阈值
     * @return 按相似度降序排列的命中列表
     */
    public List<KeywordHit> keywordSearch(String query,
                                          List<Long> knowledgeBaseIds,
                                          int topK,
                                          double minSimilarity) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        int limit = Math.max(topK, 1);
        StringBuilder sql = new StringBuilder("""
            SELECT content,
                   metadata->>'kb_id' AS kb_id,
                   word_similarity(?, content) AS score
            FROM vector_store
            WHERE word_similarity(?, content) >= ?
            """);

        boolean filterByKb = knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty();
        if (filterByKb) {
            String placeholders = knowledgeBaseIds.stream()
                .filter(Objects::nonNull)
                .map(id -> "?")
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
            if (!placeholders.isBlank()) {
                sql.append("  AND metadata->>'kb_id' IN (").append(placeholders).append(")\n");
            }
        }
        sql.append("ORDER BY score DESC\nLIMIT ").append(limit);

        try {
            Object[] args = buildKeywordArgs(query, minSimilarity, knowledgeBaseIds, filterByKb);
            List<KeywordHit> hits = jdbcTemplate.query(sql.toString(), args, (rs, rowNum) -> {
                String content = rs.getString("content");
                String kbIdStr = rs.getString("kb_id");
                double score = rs.getDouble("score");
                Long kbId = parseKbId(kbIdStr);
                return new KeywordHit(content, kbId, score);
            });
            log.info("关键词检索完成: query='{}', 命中 {} 条", query, hits.size());
            return hits;
        } catch (Exception e) {
            log.warn("关键词检索失败，本次仅依赖向量通道: {}", e.getMessage());
            return List.of();
        }
    }

    private Object[] buildKeywordArgs(String query,
                                      double minSimilarity,
                                      List<Long> knowledgeBaseIds,
                                      boolean filterByKb) {
        // 占位符顺序：SELECT 的 word_similarity(?), WHERE 的 word_similarity(?), >= ?, 然后 IN (...)
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(query);
        args.add(query);
        args.add(minSimilarity);
        if (filterByKb) {
            for (Long id : knowledgeBaseIds) {
                if (id != null) {
                    args.add(id.toString());
                }
            }
        }
        return args.toArray();
    }

    private Long parseKbId(String kbIdStr) {
        if (kbIdStr == null || kbIdStr.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(kbIdStr.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 删除指定知识库的所有向量数据
     * 使用 SQL 直接删除，利用数据库索引和删除能力
     * <p>
     * Spring AI PgVectorStore 默认表名为 vector_store，元数据存储在 metadata 字段（JSONB类型）
     * 
     * @param knowledgeBaseId 知识库ID
     * @return 删除的行数
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        log.info("开始删除知识库向量数据: kbId={}", knowledgeBaseId);
        
        /* 
         * 注意：
         * 1. metadata 字段是 json 类型，不支持 jsonb_exists 函数。
         * 2. 使用 metadata->>'key' IS NOT NULL 来替代键存在性检查，这在 json/jsonb 下都有效。
         * 3. 这种写法完全避开了 PostgreSQL 的 '?' 操作符，不会引起 JDBC 占位符冲突。
         */
        String sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_id' = ?
               OR (metadata->>'kb_id_long' IS NOT NULL AND (metadata->>'kb_id_long')::bigint = ?)
            """;
        
        try {
            // 第一个参数转为 String 匹配 kb_id，第二个参数保持 Long 匹配 kb_id_long
            int deletedRows = jdbcTemplate.update(sql, knowledgeBaseId.toString(), knowledgeBaseId);
            
            if (deletedRows > 0) {
                log.info("成功删除知识库向量数据: kbId={}, 删除行数={}", knowledgeBaseId, deletedRows);
            } else {
                log.info("未找到相关向量数据，无需删除: kbId={}", knowledgeBaseId);
            }
            
            return deletedRows;
            
        } catch (Exception e) {
            log.error("执行删除向量 SQL 失败: kbId={}, error={}", knowledgeBaseId, e.getMessage());
            // 抛出异常以触发事务回滚
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败");
        }
    }    
}
