package com.linrun.interview.document.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;
import org.springframework.core.io.ResourceLoader;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 面试平台领域知识图谱的幂等初始化器。
 *
 * <p>Neo4j 保存的是 Agent、框架、RAG 能力和基础设施之间的业务关系，
 * 不保存文档分段的父子/兄弟结构。文档分段的上下文扩展仍由 MySQL、ES 和 Redis
 * 完成，避免把检索实现细节误当成业务知识图谱。</p>
 *
 * <p>种子使用 JSON 描述，服务只执行内部固定的参数化 MERGE，不执行资源文件中的
 * 任意 Cypher。这样可以维护一份可审查的关系基线，同时支持应用启动后的补偿重跑。</p>
 */
@Slf4j
@Service
public class Neo4jDomainKnowledgeGraphService {

    private static final String PROJECTION_SOURCE = "AI_INTERVIEW_DOMAIN_SEED";
    private static final String GRAPH_VERSION = "v1";

    private static final String MERGE_ENTITY = """
        UNWIND $entities AS row
        MERGE (entity:KnowledgeEntity {entityId: row.entityId})
        SET entity += row
        """;

    private static final String MERGE_RELATION = """
        UNWIND $relations AS row
        MATCH (source:KnowledgeEntity {entityId: row.sourceEntityId})
        MATCH (target:KnowledgeEntity {entityId: row.targetEntityId})
        MERGE (source)-[relation:RELATES_TO {relationId: row.relationId}]->(target)
        SET relation += row
        """;

    private static final String PRUNE_RELATIONS = """
        MATCH (:KnowledgeEntity {projectionSource: $projectionSource})
          -[relation:RELATES_TO {projectionSource: $projectionSource}]->
          (:KnowledgeEntity {projectionSource: $projectionSource})
        WHERE NOT relation.relationId IN $relationIds
        DELETE relation
        """;

    private static final String PRUNE_ENTITIES = """
        MATCH (entity:KnowledgeEntity {projectionSource: $projectionSource})
        WHERE NOT entity.entityId IN $entityIds
        DETACH DELETE entity
        """;

    /** 早期版本写入的分段图只带这个来源标记，迁移时定向清除，不碰其他业务图数据。 */
    private static final String REMOVE_LEGACY_SEGMENT_GRAPH = """
        MATCH (node)
        WHERE node.projectionSource = 'AI_INTERVIEW_KNOWLEDGE'
        DETACH DELETE node
        """;

    private final Optional<Driver> driver;
    private final KnowledgeBaseQueryProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    public Neo4jDomainKnowledgeGraphService(Optional<Driver> driver,
                                            KnowledgeBaseQueryProperties properties,
                                            ResourceLoader resourceLoader,
                                            ObjectMapper objectMapper) {
        this.driver = driver == null ? Optional.empty() : driver;
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
    }

    public boolean available() {
        return driver.isPresent()
            && properties != null
            && properties.getMultiSource() != null
            && properties.getMultiSource().isEnabled()
            && properties.getMultiSource().getNeo4j() != null
            && properties.getMultiSource().getNeo4j().isEnabled()
            && properties.getMultiSource().getNeo4j().isSeedEnabled();
    }

    /** 应用就绪后异步初始化，Neo4j 尚未可用时由补偿任务再次重试。 */
    @Async("eventListenerExecutor")
    @EventListener(ApplicationReadyEvent.class)
    public void seedAfterApplicationReady() {
        seed();
    }

    /**
     * 读取领域种子并幂等写入 Neo4j。失败只记录日志，交给补偿任务重试。
     *
     * @return 本次是否完成写入
     */
    public boolean seed() {
        if (!available()) {
            return false;
        }
        try {
            SeedData seed = loadSeed();
            ensureSchema();
            Map<String, Object> params = new HashMap<>();
            params.put("entities", seed.entities());
            params.put("relations", seed.relations());
            params.put("entityIds", seed.entityIds());
            params.put("relationIds", seed.relationIds());
            params.put("projectionSource", PROJECTION_SOURCE);
            executeWrite(tx -> {
                tx.run(REMOVE_LEGACY_SEGMENT_GRAPH).consume();
                tx.run(MERGE_ENTITY, params).consume();
                tx.run(MERGE_RELATION, params).consume();
                tx.run(PRUNE_RELATIONS, params).consume();
                tx.run(PRUNE_ENTITIES, params).consume();
                return null;
            });
            log.info("Neo4j 领域知识图谱种子已同步: entities={}, relations={}",
                seed.entities().size(), seed.relations().size());
            return true;
        } catch (Exception e) {
            log.warn("Neo4j 领域知识图谱种子同步失败，等待补偿重试: {}", e.getMessage());
            return false;
        }
    }

    private SeedData loadSeed() {
        KnowledgeBaseQueryProperties.MultiSource.Neo4j neo4j = neo4jProperties();
        try {
            JsonNode root;
            try (InputStream input = resourceLoader.getResource(neo4j.getSeedPath()).getInputStream()) {
                root = objectMapper.readTree(input);
            }
            List<Map<String, Object>> entities = objectMapper.convertValue(
                root.path("entities"), new TypeReference<>() {});
            List<Map<String, Object>> relations = objectMapper.convertValue(
                root.path("relations"), new TypeReference<>() {});
            if (entities == null || entities.isEmpty() || relations == null) {
                throw new IllegalStateException("领域知识图谱种子为空: " + neo4j.getSeedPath());
            }
            List<Map<String, Object>> normalizedEntities = new ArrayList<>();
            Set<String> entityIds = new HashSet<>();
            for (Map<String, Object> entity : entities) {
                Map<String, Object> row = normalizeEntity(entity, neo4j.getPlatformOwnerId());
                if (!entityIds.add(String.valueOf(row.get("entityId")))) {
                    throw new IllegalStateException("领域实体 ID 重复: " + row.get("entityId"));
                }
                normalizedEntities.add(row);
            }
            List<Map<String, Object>> normalizedRelations = new ArrayList<>();
            Set<String> relationIds = new HashSet<>();
            for (Map<String, Object> relation : relations) {
                Map<String, Object> row = normalizeRelation(relation, entityIds,
                    neo4j.getPlatformOwnerId());
                if (!relationIds.add(String.valueOf(row.get("relationId")))) {
                    throw new IllegalStateException("领域关系 ID 重复: " + row.get("relationId"));
                }
                normalizedRelations.add(row);
            }
            return new SeedData(normalizedEntities, normalizedRelations,
                List.copyOf(entityIds), List.copyOf(relationIds));
        } catch (Exception e) {
            throw new IllegalStateException("无法读取 Neo4j 领域图谱种子: " + neo4j.getSeedPath(), e);
        }
    }

    private Map<String, Object> normalizeEntity(Map<String, Object> source, long platformOwnerId) {
        String entityId = required(source, "entityId");
        String name = required(source, "name");
        String type = required(source, "entityType");
        Map<String, Object> row = new HashMap<>(source);
        row.put("entityId", entityId);
        row.put("name", name);
        row.put("entityType", type);
        row.put("ownerUserId", platformOwnerId);
        row.put("scope", "PLATFORM");
        row.put("projectionSource", PROJECTION_SOURCE);
        row.put("graphVersion", GRAPH_VERSION);
        return row;
    }

    private Map<String, Object> normalizeRelation(Map<String, Object> source,
                                                   Set<String> entityIds,
                                                   long platformOwnerId) {
        String relationId = required(source, "relationId");
        String sourceEntityId = required(source, "sourceEntityId");
        String targetEntityId = required(source, "targetEntityId");
        String relationType = required(source, "relationType");
        if (!entityIds.contains(sourceEntityId) || !entityIds.contains(targetEntityId)) {
            throw new IllegalStateException("关系引用了不存在的实体: " + relationId);
        }
        Map<String, Object> row = new HashMap<>(source);
        row.put("relationId", relationId);
        row.put("sourceEntityId", sourceEntityId);
        row.put("targetEntityId", targetEntityId);
        row.put("relationType", relationType);
        row.put("ownerUserId", platformOwnerId);
        row.put("scope", "PLATFORM");
        row.put("projectionSource", PROJECTION_SOURCE);
        row.put("graphVersion", GRAPH_VERSION);
        return row;
    }

    private String required(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalStateException("领域图谱种子缺少字段: " + key);
        }
        return String.valueOf(value).trim();
    }

    private void ensureSchema() {
        if (schemaReady.get()) {
            return;
        }
        synchronized (schemaReady) {
            if (schemaReady.get()) {
                return;
            }
            try (Session session = driver.orElseThrow().session(
                SessionConfig.forDatabase(database()))) {
                session.run("CREATE CONSTRAINT ai_interview_domain_entity_id IF NOT EXISTS "
                    + "FOR (n:KnowledgeEntity) REQUIRE n.entityId IS UNIQUE").consume();
                session.run("CREATE INDEX ai_interview_domain_entity_name IF NOT EXISTS "
                    + "FOR (n:KnowledgeEntity) ON (n.normalizedName)").consume();
                session.run("CREATE INDEX ai_interview_domain_entity_type IF NOT EXISTS "
                    + "FOR (n:KnowledgeEntity) ON (n.entityType)").consume();
                session.run("CREATE INDEX ai_interview_domain_entity_owner IF NOT EXISTS "
                    + "FOR (n:KnowledgeEntity) ON (n.ownerUserId)").consume();
                schemaReady.set(true);
            } catch (Exception e) {
                schemaReady.set(false);
                throw e;
            }
        }
    }

    private void executeWrite(java.util.function.Function<TransactionContext, Void> work) {
        try (Session session = driver.orElseThrow().session(
            SessionConfig.forDatabase(database()))) {
            session.executeWrite(work::apply);
        }
    }

    private KnowledgeBaseQueryProperties.MultiSource.Neo4j neo4jProperties() {
        return properties.getMultiSource().getNeo4j();
    }

    private String database() {
        String database = neo4jProperties().getDatabase();
        return database == null || database.isBlank() ? "neo4j" : database;
    }

    private record SeedData(List<Map<String, Object>> entities,
                            List<Map<String, Object>> relations,
                            List<String> entityIds,
                            List<String> relationIds) {
    }
}
