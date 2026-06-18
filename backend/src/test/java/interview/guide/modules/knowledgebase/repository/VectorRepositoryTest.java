package interview.guide.modules.knowledgebase.repository;

import interview.guide.common.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VectorRepository 关键词通道测试：聚焦“kb_id + user_id”双层隔离的第二层。
 * <p>
 * 通过 mock JdbcTemplate 捕获实际下发的 SQL 与参数，断言：
 * <ul>
 *   <li>带 userId 时，SQL 在 kb_id 过滤外叠加 metadata->>'user_id' = ?，且 userId 以字符串追加到参数末尾；</li>
 *   <li>userId 为 null（评测/无登录上下文）时退化为仅 kb_id 过滤，兼容历史行为。</li>
 * </ul>
 */
@DisplayName("向量存储 Repository - 关键词通道 user_id 纵深防御测试")
class VectorRepositoryTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final VectorRepository repository = new VectorRepository(jdbcTemplate);

    @AfterEach
    void tearDown() {
        // 避免 ThreadLocal 在测试间泄漏
        UserContext.clear();
    }

    @Test
    @DisplayName("带 userId 时，关键词 SQL 叠加 user_id 过滤条件且参数正确")
    void keywordSearchShouldAppendUserIdFilter() {
        when(jdbcTemplate.query(anyString(), any(Object[].class), any(RowMapper.class)))
            .thenReturn(List.<VectorRepository.KeywordHit>of());

        repository.keywordSearch("退款一致性", List.of(1L, 2L), 10, 0.3, 999L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), argsCaptor.capture(), any(RowMapper.class));

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("metadata->>'user_id' = ?"),
            "关键词 SQL 应包含 user_id 第二层过滤条件，实际: " + sql);
        assertTrue(sql.contains("metadata->>'kb_id' IN"),
            "kb_id 第一层过滤应保留，实际: " + sql);

        Object[] args = argsCaptor.getValue();
        assertEquals("999", args[args.length - 1],
            "user_id 参数应以字符串形式追加到末尾，实际: " + java.util.Arrays.toString(args));
    }

    @Test
    @DisplayName("userId 为 null 时退化为仅 kb_id 过滤，兼容历史行为")
    void keywordSearchWithoutUserIdShouldNotFilterUser() {
        when(jdbcTemplate.query(anyString(), any(Object[].class), any(RowMapper.class)))
            .thenReturn(List.<VectorRepository.KeywordHit>of());

        repository.keywordSearch("退款一致性", List.of(1L), 10, 0.3, null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(Object[].class), any(RowMapper.class));
        assertFalse(sqlCaptor.getValue().contains("user_id"),
            "无 userId 时不应叠加 user_id 条件，实际: " + sqlCaptor.getValue());
    }

    @Test
    @DisplayName("清理无 chunk_hash 的历史向量行")
    void deleteHashlessChunksShouldUseKbIdAndMissingHashCondition() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2);

        int deleted = repository.deleteHashlessChunksByKbId(1L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), argsCaptor.capture());
        assertEquals(2, deleted);
        assertTrue(sqlCaptor.getValue().contains("metadata->>'chunk_hash' IS NULL"),
            "应只清理无 chunk_hash 的历史行，实际: " + sqlCaptor.getValue());
        assertEquals("1", argsCaptor.getValue()[0]);
    }

    @Test
    @DisplayName("复用向量时按 kb_id 与 chunk_hash 更新 metadata")
    void updateMetadataShouldMatchKbIdAndChunkHash() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        int updated = repository.updateMetadataByKbIdAndChunkHash(
            1L, "hash-1", Map.of("chunk_index", "2", "document_title", "新版标题"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), argsCaptor.capture());
        Object[] args = argsCaptor.getValue();
        assertEquals(1, updated);
        assertTrue(sqlCaptor.getValue().contains("metadata = COALESCE(metadata"),
            "应通过 JSONB 合并刷新 metadata，实际: " + sqlCaptor.getValue());
        assertTrue(String.valueOf(args[0]).contains("新版标题"));
        assertEquals("1", args[1]);
        assertEquals("hash-1", args[2]);
    }
}
