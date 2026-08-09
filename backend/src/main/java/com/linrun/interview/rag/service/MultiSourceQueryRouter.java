package com.linrun.interview.rag.service;
import com.linrun.interview.rag.model.RagQueryTrace;

import com.fasterxml.jackson.databind.JsonNode;
import com.linrun.interview.infra.json.JsonUtil;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.router.QueryRouter;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * ES / MySQL / Neo4j 三源查询路由器。
 *
 * <p>先用低成本规则给出安全默认值，再让 LLM 输出结构化策略；LLM 失败、JSON 异常或
 * 目标数据源未配置时统一回退 ES。路由只返回一个主数据源，SQL/Cypher 检索器内部再
 * 负责空结果回退，避免多源结果相互污染非结构化 BGE 重排。Neo4j 只承载业务实体关系，
 * 不承载文档分段父子关系。</p>
 */
@Slf4j
public class MultiSourceQueryRouter implements QueryRouter {

  public enum Source {
    KNOWLEDGE_BASE("knowledge_base"),
    RELATIONAL_DB("relational_db"),
    GRAPH_DB("graph_db");

    private final String wireValue;

    Source(String wireValue) {
      this.wireValue = wireValue;
    }

    public String wireValue() {
      return wireValue;
    }

    public static Source from(String value) {
      if (value == null) {
        return KNOWLEDGE_BASE;
      }
      for (Source source : values()) {
        if (source.wireValue.equalsIgnoreCase(value.trim())
            || source.name().equalsIgnoreCase(value.trim())) {
          return source;
        }
      }
      return KNOWLEDGE_BASE;
    }
  }

  public record Decision(String intent, Source source, double confidence, String reasoning) {
  }

  private static final String ROUTE_PROMPT = """
      你是技术面试知识平台的数据源路由器。只分析问题，不直接回答。
      请选择最合适的一个数据源：
      - knowledge_base：文档、面试知识、代码解释、总结等非结构化内容
      - relational_db：用户简历/面试记录/统计/列表/时间范围/数值聚合等结构化数据
      - graph_db：平台领域实体关系、技术栈关联、Agent/框架依赖、上下游和路径问题；
        文档分段父子关系不属于图数据库路由，仍由知识库检索和上下文扩展处理
      必须只输出 JSON：
      {"intent":"核心意图","strategy":"knowledge_base|relational_db|graph_db","confidence":0.0,"reasoning":"判断依据"}
      用户问题：{{query}}
      """;

  private final Map<Source, ContentRetriever> retrievers;
  private final ContentRetriever fallbackRetriever;
  private final ChatModel chatModel;
  private final boolean llmEnabled;
  private final Consumer<String> progressCallback;
  private final RagQueryTrace trace;
  private final AtomicBoolean progressSent = new AtomicBoolean(false);
  private final AtomicBoolean routeRecorded = new AtomicBoolean(false);

  public MultiSourceQueryRouter(Map<Source, ContentRetriever> retrievers,
                                ChatModel chatModel,
                                boolean llmEnabled,
                                Consumer<String> progressCallback,
                                RagQueryTrace trace) {
    EnumMap<Source, ContentRetriever> copy = new EnumMap<>(Source.class);
    if (retrievers != null) {
      copy.putAll(retrievers);
    }
    this.retrievers = Map.copyOf(copy);
    this.fallbackRetriever = this.retrievers.get(Source.KNOWLEDGE_BASE);
    this.chatModel = chatModel;
    this.llmEnabled = llmEnabled;
    this.progressCallback = progressCallback;
    this.trace = trace;
  }

  @Override
  public Collection<ContentRetriever> route(Query query) {
    if (progressCallback != null && progressSent.compareAndSet(false, true)) {
      progressCallback.accept("正在路由数据源...");
    }
    String question = query == null || query.text() == null ? "" : query.text();
    Decision decision = decide(question);
    ContentRetriever selected = retrievers.get(decision.source());
    if (selected == null) {
      selected = fallbackRetriever;
      decision = new Decision(decision.intent(), Source.KNOWLEDGE_BASE,
          decision.confidence(), decision.reasoning() + "；目标数据源未启用，回退 ES");
    }
    if (trace != null && routeRecorded.compareAndSet(false, true)) {
      trace.route(decision.source().wireValue(), decision.intent(), decision.confidence(), decision.reasoning());
    }
    return selected == null ? List.of() : List.of(selected);
  }

  public Decision decide(String question) {
    Decision heuristic = heuristic(question);
    if (!llmEnabled || chatModel == null || question == null || question.isBlank()) {
      return heuristic;
    }
    try {
      String response = chatModel.chat(ROUTE_PROMPT.replace("{{query}}", question.strip()));
      JsonNode json = JsonUtil.fixAndParse(response);
      Source source = Source.from(json.path("strategy").asText(null));
      double confidence = json.path("confidence").asDouble(heuristic.confidence());
      if (!Double.isFinite(confidence)) {
        confidence = heuristic.confidence();
      }
      confidence = Math.max(0.0, Math.min(1.0, confidence));
      String intent = json.path("intent").asText(heuristic.intent());
      String reasoning = json.path("reasoning").asText(heuristic.reasoning());
      if (json.path("strategy").isMissingNode() || json.path("strategy").isNull()) {
        return heuristic;
      }
      return new Decision(intent, source, confidence, reasoning);
    } catch (Exception e) {
      log.warn("多数据源 LLM 路由失败，使用规则路由: {}", e.getMessage());
      return heuristic;
    }
  }

  private Decision heuristic(String question) {
    String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
    // 分段父子/兄弟是 ES 命中后的上下文扩展，不是 Neo4j 领域图谱关系。
    if (containsAny(normalized, "父子分段", "父子切片", "兄弟分段", "兄弟切片", "分段关系", "切片关系", "chunk")) {
      return new Decision("文档分段语义问答", Source.KNOWLEDGE_BASE, 0.9,
          "命中文档分段结构关键词，保留 ES 上下文扩展");
    }
    if (containsAny(normalized, "调用链", "依赖关系", "上下游", "关联", "关系", "路径", "拓扑", "图谱", "谁引用", "neo4j",
        "集成", "依赖", "技术栈", "如何连接", "怎么配合")) {
      return new Decision("实体关系查询", Source.GRAPH_DB, 0.86, "命中关系/路径类关键词");
    }
    if (containsAny(normalized, "我的简历", "简历记录", "面试记录", "多少分", "统计", "数量", "列表", "最近", "时间范围", "sql", "数据库")) {
      return new Decision("结构化记录查询", Source.RELATIONAL_DB, 0.84, "命中记录/统计/结构化查询关键词");
    }
    return new Decision("文档语义问答", Source.KNOWLEDGE_BASE, 0.72, "默认使用非结构化知识库");
  }

  private boolean containsAny(String text, String... keywords) {
    for (String keyword : keywords) {
      if (text.contains(keyword)) {
        return true;
      }
    }
    return false;
  }
}
