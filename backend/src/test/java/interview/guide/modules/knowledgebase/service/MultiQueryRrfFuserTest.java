package interview.guide.modules.knowledgebase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("多路召回 RRF 融合测试")
class MultiQueryRrfFuserTest {

    private Document doc(String text, double score) {
        return Document.builder().text(text).score(score).metadata(new HashMap<>(Map.of())).build();
    }

    @Test
    @DisplayName("两路均命中的文档跨路累加后排名最前")
    void shouldRankCrossRouteHitsFirst() {
        List<Document> routeA = List.of(doc("共享文档", 0.8), doc("仅A", 0.7));
        List<Document> routeB = List.of(doc("共享文档", 0.6), doc("仅B", 0.5));

        List<Document> fused = MultiQueryRrfFuser.fuse(List.of(routeA, routeB), 60, 10);

        assertThat(fused).hasSize(3);
        assertThat(fused.get(0).getText()).isEqualTo("共享文档");
    }

    @Test
    @DisplayName("按 finalTopK 截断")
    void shouldLimitByTopK() {
        List<Document> route = List.of(doc("a", 0.9), doc("b", 0.8), doc("c", 0.7), doc("d", 0.6));

        List<Document> fused = MultiQueryRrfFuser.fuse(List.of(route), 60, 2);

        assertThat(fused).hasSize(2);
    }

    @Test
    @DisplayName("单路召回等价于按原顺序截断")
    void shouldPreserveOrderForSingleRoute() {
        List<Document> route = List.of(doc("a", 0.9), doc("b", 0.8));

        List<Document> fused = MultiQueryRrfFuser.fuse(List.of(route), 60, 10);

        assertThat(fused).extracting(Document::getText).containsExactly("a", "b");
    }

    @Test
    @DisplayName("保留各路最大相似度写入 final_score")
    void shouldKeepMaxScoreAsFinalScore() {
        List<Document> routeA = List.of(doc("x", 0.5));
        List<Document> routeB = List.of(doc("x", 0.9));

        List<Document> fused = MultiQueryRrfFuser.fuse(List.of(routeA, routeB), 60, 10);

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).getMetadata().get("final_score")).isEqualTo(0.9);
    }

    @Test
    @DisplayName("空路由返回空列表")
    void shouldReturnEmptyForEmptyRoutes() {
        assertThat(MultiQueryRrfFuser.fuse(List.of(), 60, 10)).isEmpty();
        assertThat(MultiQueryRrfFuser.fuse(List.of(List.of()), 60, 10)).isEmpty();
    }
}
