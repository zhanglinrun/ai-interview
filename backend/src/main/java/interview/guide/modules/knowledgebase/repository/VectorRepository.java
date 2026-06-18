package interview.guide.modules.knowledgebase.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 向量存储Repository
 * 负责向量数据的增删改查操作，以及混合检索的关键词通道。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class VectorRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    /**
     * 关键词检索命中结果。
     *
     * @param content 文档正文
     * @param kbId    所属知识库ID（来自 metadata->>'kb_id'，可能为 null）
     * @param score   pg_trgm word_similarity 得分，范围 0~1
     * @param metadata 文档元数据，用于来源展示和后续过滤
     */
    public record KeywordHit(String content, Long kbId, double score, Map<String, Object> metadata) {
        public KeywordHit(String content, Long kbId, double score) {
            this(content, kbId, score, Map.of());
        }
    }

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
        return keywordSearch(query, knowledgeBaseIds, topK, minSimilarity, null);
    }

    /**
     * 关键词检索通道（带 user_id 纵深防御）。
     * <p>
     * userId 非空时，在 kb_id 过滤之外再叠加 metadata->>'user_id' = ? 条件，
     * 与向量通道共同构成“kb_id + user_id”双层隔离：即便某条 chunk 的 kb_id
     * 标识被污染或绕过，只要它不属于当前用户也会被关键词通道剔除。
     * userId 为 null（评测、无登录上下文等）时退化为仅 kb_id 过滤，兼容历史行为。
     */
    public List<KeywordHit> keywordSearch(String query,
                                          List<Long> knowledgeBaseIds,
                                          int topK,
                                          double minSimilarity,
                                          Long userId) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        int limit = Math.max(topK, 1);
        StringBuilder sql = new StringBuilder("""
            SELECT content,
                   metadata->>'kb_id' AS kb_id,
                   metadata->>'source_name' AS source_name,
                   metadata->>'document_title' AS document_title,
                   metadata->>'category' AS category,
                   metadata->>'section_title' AS section_title,
                   metadata->>'section_level' AS section_level,
                   metadata->>'section_index' AS section_index,
                   metadata->>'chunk_index' AS chunk_index,
                   metadata->>'chunk_count' AS chunk_count,
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
        boolean filterByUser = userId != null;
        if (filterByUser) {
            sql.append("  AND metadata->>'user_id' = ?\n");
        }
        sql.append("ORDER BY score DESC\nLIMIT ").append(limit);

        try {
            Object[] args = buildKeywordArgs(query, minSimilarity, knowledgeBaseIds, filterByKb, userId, filterByUser);
            List<KeywordHit> hits = jdbcTemplate.query(sql.toString(), args, (rs, rowNum) -> {
                String content = rs.getString("content");
                String kbIdStr = rs.getString("kb_id");
                double score = rs.getDouble("score");
                Long kbId = parseKbId(kbIdStr);
                Map<String, Object> metadata = new HashMap<>();
                putIfNotBlank(metadata, "kb_id", kbIdStr);
                putIfNotBlank(metadata, "source_name", rs.getString("source_name"));
                putIfNotBlank(metadata, "document_title", rs.getString("document_title"));
                putIfNotBlank(metadata, "category", rs.getString("category"));
                putIfNotBlank(metadata, "section_title", rs.getString("section_title"));
                putIfNotBlank(metadata, "section_level", rs.getString("section_level"));
                putIfNotBlank(metadata, "section_index", rs.getString("section_index"));
                putIfNotBlank(metadata, "chunk_index", rs.getString("chunk_index"));
                putIfNotBlank(metadata, "chunk_count", rs.getString("chunk_count"));
                return new KeywordHit(content, kbId, score, metadata);
            });
            log.info("关键词检索完成: query='{}', 命中 {} 条", query, hits.size());
            return hits;
        } catch (Exception e) {
            log.warn("关键词检索失败，本次仅依赖向量通道: {}", e.getMessage());
            return List.of();
        }
    }

    private void putIfNotBlank(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    private Object[] buildKeywordArgs(String query,
                                      double minSimilarity,
                                      List<Long> knowledgeBaseIds,
                                      boolean filterByKb,
                                      Long userId,
                                      boolean filterByUser) {
        // 占位符顺序：SELECT 的 word_similarity(?), WHERE 的 word_similarity(?), >= ?,
        // 然后 kb_id IN (...), 最后 user_id = ?
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
        if (filterByUser) {
            args.add(userId.toString());
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

    /**
     * 查询同知识库、同父段落（document|section）的兄弟 chunk 文本，按 chunk_index 升序。
     * <p>
     * 用于检索侧 small-to-big：命中小 chunk 后聚合同段更大上下文喂给 LLM。
     * userId 非空时叠加 user_id 过滤，与检索主链路保持双层隔离。
     *
     * @return 兄弟 chunk 文本列表（不含分数，按 chunk 顺序）
     */
    public List<String> findSiblingChunkTexts(Long knowledgeBaseId, String parentSection, int limit, Long userId) {
        if (knowledgeBaseId == null || parentSection == null || parentSection.isBlank()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("""
            SELECT content FROM vector_store
            WHERE metadata->>'kb_id' = ?
              AND metadata->>'parent_section' = ?
            """);
        List<Object> args = new ArrayList<>();
        args.add(knowledgeBaseId.toString());
        args.add(parentSection);
        if (userId != null) {
            sql.append("  AND metadata->>'user_id' = ?\n");
            args.add(userId.toString());
        }
        sql.append("ORDER BY COALESCE(NULLIF(metadata->>'chunk_index', '')::int, 0) ASC, id ASC\n");
        sql.append("LIMIT ").append(Math.max(limit, 1));
        try {
            return jdbcTemplate.queryForList(sql.toString(), String.class, args.toArray());
        } catch (Exception e) {
            log.warn("查询兄弟 chunk 文本失败，退回不扩展: kbId={}, section={}, error={}",
                knowledgeBaseId, parentSection, e.getMessage());
            return List.of();
        }
    }

    /**
     * 查询某知识库已入库 chunk 的内容哈希集合，用于增量更新时的 diff。
     * <p>
     * 返回 metadata->>'chunk_hash' 非空的去重集合。历史无 chunk_hash 的行不计入，
     * 它们会在增量 diff 中被当作"失效"删除并重建（逐步迁移到带 hash 的新格式）。
     */
    public Set<String> findChunkHashesByKbId(Long knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return Set.of();
        }
        String sql = "SELECT DISTINCT metadata->>'chunk_hash' AS chunk_hash "
                + "FROM vector_store WHERE metadata->>'kb_id' = ?";
        try {
            List<String> hashes = jdbcTemplate.queryForList(sql, String.class, knowledgeBaseId.toString());
            return hashes.stream()
                    .filter(h -> h != null && !h.isBlank())
                    .collect(Collectors.toCollection(HashSet::new));
        } catch (Exception e) {
            log.warn("查询已入库 chunk 哈希失败，本次按全量重建处理: kbId={}, error={}",
                    knowledgeBaseId, e.getMessage());
            return Set.of();
        }
    }

    /**
     * 清理历史无 chunk_hash 的旧向量行，避免增量模式下旧格式数据继续参与检索。
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteHashlessChunksByKbId(Long knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return 0;
        }
        String sql = """
            DELETE FROM vector_store
            WHERE metadata->>'kb_id' = ?
              AND (metadata->>'chunk_hash' IS NULL OR metadata->>'chunk_hash' = '')
            """;
        try {
            int deleted = jdbcTemplate.update(sql, knowledgeBaseId.toString());
            if (deleted > 0) {
                log.info("清理无 chunk_hash 的历史向量行: kbId={}, 删除 {} 条", knowledgeBaseId, deleted);
            }
            return deleted;
        } catch (Exception e) {
            log.error("清理历史向量行失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "清理历史向量数据失败");
        }
    }

    /**
     * 内容未变化时复用旧 embedding，但同步更新来源、分片序号、用户隔离等 metadata。
     */
    @Transactional(rollbackFor = Exception.class)
    public int updateMetadataByKbIdAndChunkHash(Long knowledgeBaseId,
                                                String chunkHash,
                                                Map<String, Object> metadata) {
        if (knowledgeBaseId == null || chunkHash == null || chunkHash.isBlank()
            || metadata == null || metadata.isEmpty()) {
            return 0;
        }
        String metadataJson;
        try {
            metadataJson = OBJECT_MAPPER.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "序列化向量元数据失败");
        }
        String sql = """
            UPDATE vector_store
            SET metadata = COALESCE(metadata, '{}'::jsonb) || ?::jsonb
            WHERE metadata->>'kb_id' = ?
              AND metadata->>'chunk_hash' = ?
            """;
        try {
            return jdbcTemplate.update(sql, metadataJson, knowledgeBaseId.toString(), chunkHash);
        } catch (Exception e) {
            log.error("更新 chunk metadata 失败: kbId={}, chunkHash={}, error={}",
                knowledgeBaseId, chunkHash, e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "更新向量元数据失败");
        }
    }

    /**
     * 增量删除：删除指定知识库下、内容哈希命中给定集合的 chunk（失效 chunk）。
     * <p>
     * 用于增量更新时清理"旧 split 有、新 split 没有"的 chunk，
     * 替代每次都全量删重建，只清理真正失效的部分。
     *
     * @return 实际删除行数
     */
    @Transactional(rollbackFor = Exception.class)
    public int deleteByKbIdAndChunkHashes(Long knowledgeBaseId, Collection<String> chunkHashes) {
        if (knowledgeBaseId == null || chunkHashes == null || chunkHashes.isEmpty()) {
            return 0;
        }
        Set<String> normalized = chunkHashes.stream()
                .filter(h -> h != null && !h.isBlank())
                .collect(Collectors.toSet());
        if (normalized.isEmpty()) {
            return 0;
        }
        String placeholders = normalized.stream().map(h -> "?").collect(Collectors.joining(", "));
        String sql = "DELETE FROM vector_store "
                + "WHERE metadata->>'kb_id' = ? AND metadata->>'chunk_hash' IN (" + placeholders + ")";
        List<Object> args = new ArrayList<>();
        args.add(knowledgeBaseId.toString());
        args.addAll(normalized);
        try {
            int deleted = jdbcTemplate.update(sql, args.toArray());
            log.info("增量删除失效 chunk: kbId={}, 删除 {} 条", knowledgeBaseId, deleted);
            return deleted;
        } catch (Exception e) {
            log.error("增量删除失效 chunk 失败: kbId={}, error={}",
                    knowledgeBaseId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除失效向量数据失败");
        }
    }
}
