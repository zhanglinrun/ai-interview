package interview.guide.modules.knowledgebase.service;

import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档重排服务：调用 DashScope gte-rerank 对融合后的候选文档重排。
 * <p>
 * DashScope rerank 接口不是 OpenAI 兼容格式，因此用 RestClient 直连
 * text-rerank 端点，而非复用 OpenAI ChatClient。任何失败都会安全降级，
 * 返回入参原顺序，由上层退回 RRF 融合排序。
 */
@Slf4j
@Service
public class RerankService {

    private static final int DEFAULT_TIMEOUT_MS = 3000;
    private static final String RERANK_PATH =
        "/api/v1/services/rerank/text-rerank/text-rerank";

    private final KnowledgeBaseQueryProperties.Rerank rerankProps;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;
    private final boolean available;

    public RerankService(KnowledgeBaseQueryProperties queryProperties,
                         LlmProviderProperties llmProviderProperties) {
        this.rerankProps = queryProperties.getRerank();

        ProviderConfig dashscope = llmProviderProperties.getProviders() != null
            ? llmProviderProperties.getProviders().get("dashscope")
            : null;
        this.apiKey = dashscope != null ? dashscope.getApiKey() : null;

        String baseUrl = "https://dashscope.aliyuncs.com";
        int timeoutMs = resolveTimeoutMs(rerankProps.getTimeoutMs());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .build();

        this.available = apiKey != null && !apiKey.isBlank();
        if (!available) {
            log.warn("[RerankService] 未找到 dashscope API Key，重排不可用，将始终退回融合排序");
        }
    }

    /**
     * 是否可用（开关开启且凭证就绪）。
     */
    public boolean isEnabled() {
        return rerankProps.isEnabled() && available;
    }

    /**
     * 对候选文档按与 query 的相关性重排，返回前 topN。
     * 任何异常都安全降级：返回入参原顺序的前 topN。
     *
     * @param query     查询文本
     * @param documents 融合后的候选文档（已带 RRF 顺序）
     * @return 重排后的文档（每个文档的 score 被更新为 rerank 相关性分），最多 topN 个
     */
    public List<Document> rerank(String query, List<Document> documents) {
        int topN = Math.max(1, rerankProps.getTopN());
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        if (documents.size() <= topN) {
            return documents;
        }
        if (!isEnabled() || query == null || query.isBlank()) {
            return capList(documents, topN);
        }

        try {
            List<String> texts = documents.stream().map(Document::getText).toList();
            Map<String, Object> input = Map.of("query", query, "documents", texts);
            Map<String, Object> parameters = Map.of(
                "return_documents", false,
                "top_n", Math.min(topN, texts.size())
            );
            Map<String, Object> body = Map.of(
                "model", rerankProps.getModel(),
                "input", input,
                "parameters", parameters
            );

            String responseText = restClient.post()
                .uri(RERANK_PATH)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);

            JsonNode response = (responseText == null || responseText.isBlank())
                ? null
                : objectMapper.readTree(responseText);

            List<Document> reranked = parseReranked(response, documents);
            if (reranked.isEmpty()) {
                log.warn("[RerankService] 重排返回空结果，退回融合排序");
                return capList(documents, topN);
            }
            log.info("[RerankService] 重排完成: 候选 {} -> 保留 {}", documents.size(), reranked.size());
            return reranked;
        } catch (Exception e) {
            log.warn("[RerankService] 重排调用失败，退回融合排序: {}", e.getMessage(), e);
            return capList(documents, topN);
        }
    }

    private List<Document> parseReranked(JsonNode response, List<Document> documents) {
        if (response == null) {
            return List.of();
        }
        JsonNode results = response.path("output").path("results");
        if (!results.isArray() || results.isEmpty()) {
            return List.of();
        }

        List<Document> reranked = new ArrayList<>();
        for (JsonNode result : results) {
            int index = result.path("index").asInt(-1);
            if (index < 0 || index >= documents.size()) {
                continue;
            }
            double relevance = result.path("relevance_score").asDouble(0.0);
            Document original = documents.get(index);
            Document scored = original.mutate()
                .score(relevance)
                .build();
            reranked.add(scored);
        }
        return reranked;
    }

    private List<Document> capList(List<Document> documents, int topN) {
        if (documents.size() <= topN) {
            return documents;
        }
        return new ArrayList<>(documents.subList(0, topN));
    }

    private static int resolveTimeoutMs(long configuredTimeoutMs) {
        if (configuredTimeoutMs <= 0) {
            return DEFAULT_TIMEOUT_MS;
        }
        return (int) Math.min(configuredTimeoutMs, Integer.MAX_VALUE);
    }
}
