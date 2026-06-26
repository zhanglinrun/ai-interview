package com.linrun.interview.modules.knowledgebase.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import com.linrun.interview.common.config.LlmProviderProperties;
import com.linrun.interview.common.config.LlmProviderProperties.ProviderConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档重排服务（LangChain4j {@link ScoringModel} 实现）：调用 DashScope gte-rerank 对候选片段重排。
 *
 * <p>移植对齐 know-engine 的 rerank 思路（ScoringModel 供 ReRankingContentAggregator 调用），
 * 但 know-engine 用本地 ONNX {@code BgeScoringModel}，本项目复用现有 DashScope gte-rerank 云端 API
 * （非 OpenAI 兼容格式，用 RestClient 直连 text-rerank 端点）。
 *
 * <p>实现 {@link ScoringModel#scoreAll(List, String)} 返回每个 segment 与 query 的相关性分列表
 * （顺序与输入一致），由 {@code ReRankingContentAggregator} 负责按分排序与截断；任何失败安全降级，
 * 返回等分（0.0）列表让上层退回原序。
 */
@Slf4j
@Service
public class RerankService implements ScoringModel {

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

        String baseUrl = rerankProps.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://dashscope.aliyuncs.com";
        }
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
            log.warn("[RerankService] 未找到 dashscope API Key，重排不可用，将始终退回原序");
        }
    }

    /**
     * 是否可用（开关开启且凭证就绪）。
     */
    public boolean isEnabled() {
        return rerankProps.isEnabled() && available;
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        if (segments == null || segments.isEmpty()) {
            return Response.from(List.of());
        }
        if (!isEnabled() || query == null || query.isBlank()) {
            return Response.from(zeroScores(segments.size()));
        }
        try {
            List<Double> scores = scoreAllInternal(query, segments);
            return Response.from(scores);
        } catch (Exception e) {
            log.warn("[RerankService] scoreAll 失败，返回等分降级: {}", e.getMessage(), e);
            return Response.from(zeroScores(segments.size()));
        }
    }

    private List<Double> scoreAllInternal(String query, List<TextSegment> segments) {
        List<String> texts = segments.stream().map(TextSegment::text).toList();
        Map<String, Object> input = Map.of("query", query, "documents", texts);
        Map<String, Object> parameters = Map.of(
            "return_documents", false,
            "top_n", texts.size()
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

        return parseScores(responseText, segments.size());
    }

    /**
     * DashScope rerank 返回的 results 按 relevance 降序，每项含 index（对应输入位置）和 relevance_score。
     * 这里还原成与输入 segments 顺序一致的分列表。
     */
    private List<Double> parseScores(String responseText, int size) {
        List<Double> scores = new ArrayList<>(zeroScores(size));
        if (responseText == null || responseText.isBlank()) {
            return scores;
        }
        try {
            JsonNode response = objectMapper.readTree(responseText);
            JsonNode results = response.path("output").path("results");
            if (!results.isArray() || results.isEmpty()) {
                return scores;
            }
            for (JsonNode result : results) {
                int index = result.path("index").asInt(-1);
                if (index < 0 || index >= size) {
                    continue;
                }
                double relevance = result.path("relevance_score").asDouble(0.0);
                scores.set(index, relevance);
            }
        } catch (Exception e) {
            log.warn("[RerankService] 解析 rerank 响应失败，退回等分: {}", e.getMessage());
        }
        return scores;
    }

    private List<Double> zeroScores(int size) {
        List<Double> zeros = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            zeros.add(0.0);
        }
        return zeros;
    }

    private static int resolveTimeoutMs(long configuredTimeoutMs) {
        if (configuredTimeoutMs <= 0) {
            return DEFAULT_TIMEOUT_MS;
        }
        return (int) Math.min(configuredTimeoutMs, Integer.MAX_VALUE);
    }
}
