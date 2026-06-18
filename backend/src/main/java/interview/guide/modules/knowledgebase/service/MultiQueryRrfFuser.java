package interview.guide.modules.knowledgebase.service;

import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 多路召回的 RRF（Reciprocal Rank Fusion）融合器。
 * <p>
 * HyDE / rewrite / 原问题等多个候选 query 各做一次检索后，每路结果按相关性排好序，
 * 这里按排名 1/(k+rank+1) 跨路累加，分高者优先；同一文档（按正文去重）跨路累加分数。
 * 抽成纯函数便于单测，与检索主流程解耦。
 */
final class MultiQueryRrfFuser {

    private MultiQueryRrfFuser() {
    }

    /**
     * @param routes 每路召回结果（已按相关性降序排好）
     * @param rrfK   RRF 融合常数 k，越大越平滑各路排名差异
     * @param topK   融合后保留的文档数
     * @return 按融合分降序的文档（每个文档 metadata 里写入跨路最大相似度 final_score）
     */
    static List<Document> fuse(List<List<Document>> routes, int rrfK, int topK) {
        Map<String, Document> docByText = new LinkedHashMap<>();
        Map<String, Double> rrfScore = new LinkedHashMap<>();
        Map<String, Double> bestScore = new LinkedHashMap<>();

        if (routes == null || routes.isEmpty()) {
            return List.of();
        }
        for (List<Document> route : routes) {
            if (route == null) {
                continue;
            }
            for (int rank = 0; rank < route.size(); rank++) {
                Document doc = route.get(rank);
                String key = doc.getText();
                if (key == null) {
                    continue;
                }
                docByText.putIfAbsent(key, doc);
                rrfScore.merge(key, 1.0 / (rrfK + rank + 1), Double::sum);
                double score = doc.getScore() == null ? 0.0 : doc.getScore();
                bestScore.merge(key, score, Math::max);
            }
        }

        List<Document> fused = rrfScore.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(Math.max(topK, 1))
            .map(entry -> docByText.get(entry.getKey()))
            .filter(Objects::nonNull)
            .toList();

        // 把跨路最大向量相似度写回 metadata 作为展示/置信度口径；
        // 重排开启时会被 rerank 分覆盖，未开启时直接复用。
        for (Document doc : fused) {
            Double score = bestScore.get(doc.getText());
            if (score != null) {
                doc.getMetadata().put("final_score", score);
            }
        }
        return fused;
    }
}
