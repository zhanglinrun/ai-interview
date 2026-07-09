package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.modules.knowledgebase.model.GraphViewDTO;
import com.linrun.interview.modules.knowledgebase.model.GraphViewDTO.Edge;
import com.linrun.interview.modules.knowledgebase.model.GraphViewDTO.Node;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图谱只读查询服务：把 Neo4j 里的技能/知识图谱查成前端可视化用的 {@link GraphViewDTO}。
 *
 * <p>图 schema：{@code (:Skill)-[:COVERS]->(:Concept)}（技能预置）、
 * {@code (:Concept)-[:BELONGS_TO]->(:Document)} 与 {@code (:Concept)-[:RELATES_TO]->(:Concept)}（文档向量化时同步）。
 *
 * <p>Neo4j 未启用或 Driver 不在（{@code app.ai.rag.graph.enabled=false} 或未连图库）时优雅降级返回空图，
 * 前端页面正常渲染空状态，不抛错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphQueryService {

    private final KnowledgeBaseQueryProperties queryProperties;

    @Autowired(required = false)
    private Driver neo4jDriver;

    public boolean available() {
        return neo4jDriver != null && queryProperties.getGraph().isEnabled();
    }

    /** 技能图谱概览：Skill-[:COVERS]->Concept，按上限截断。 */
    public GraphViewDTO overview(int limit) {
        if (!available()) {
            return GraphViewDTO.empty();
        }
        int lim = Math.min(Math.max(limit, 1), 500);
        try (Session session = neo4jDriver.session()) {
            return session.executeRead(tx -> {
                Map<String, Node> nodes = new LinkedHashMap<>();
                List<Edge> edges = new ArrayList<>();
                var result = tx.run(
                    "MATCH (s:Skill)-[:COVERS]->(c:Concept) "
                        + "RETURN s.name AS skill, c.name AS concept LIMIT $limit",
                    Map.of("limit", lim));
                while (result.hasNext()) {
                    var record = result.next();
                    String skill = record.get("skill").asString(null);
                    String concept = record.get("concept").asString(null);
                    if (skill == null || concept == null) {
                        continue;
                    }
                    String skillId = "Skill:" + skill;
                    String conceptId = "Concept:" + concept;
                    nodes.putIfAbsent(skillId, new Node(skillId, skill, "Skill"));
                    nodes.putIfAbsent(conceptId, new Node(conceptId, concept, "Concept"));
                    edges.add(new Edge(skillId, conceptId, "COVERS"));
                }
                return new GraphViewDTO(new ArrayList<>(nodes.values()), edges);
            });
        } catch (Exception e) {
            log.warn("[GraphQueryService] 图谱概览查询失败: {}", e.getMessage(), e);
            return GraphViewDTO.empty();
        }
    }

    /** 概念/技能子图：以给定名称的节点为中心，展开一跳邻居（任意关系类型，无向展示）。 */
    public GraphViewDTO neighbors(String name, int limit) {
        if (!available() || name == null || name.isBlank()) {
            return GraphViewDTO.empty();
        }
        int lim = Math.min(Math.max(limit, 1), 200);
        try (Session session = neo4jDriver.session()) {
            return session.executeRead(tx -> {
                Map<String, Node> nodes = new LinkedHashMap<>();
                List<Edge> edges = new ArrayList<>();
                var result = tx.run(
                    "MATCH (center {name: $name}) "
                        + "OPTIONAL MATCH (center)-[r]-(n) "
                        + "RETURN labels(center)[0] AS clabel, center.name AS cname, "
                        + "type(r) AS rel, labels(n)[0] AS nlabel, n.name AS nname LIMIT $limit",
                    Map.of("name", name, "limit", lim));
                while (result.hasNext()) {
                    var record = result.next();
                    String cLabel = record.get("clabel").asString("Concept");
                    String cName = record.get("cname").asString(name);
                    String centerId = cLabel + ":" + cName;
                    nodes.putIfAbsent(centerId, new Node(centerId, cName, cLabel));

                    if (record.get("rel").isNull() || record.get("nname").isNull()) {
                        continue;
                    }
                    String rel = record.get("rel").asString();
                    String nLabel = record.get("nlabel").asString("Concept");
                    String nName = record.get("nname").asString();
                    String neighborId = nLabel + ":" + nName;
                    nodes.putIfAbsent(neighborId, new Node(neighborId, nName, nLabel));
                    edges.add(new Edge(centerId, neighborId, rel));
                }
                return new GraphViewDTO(new ArrayList<>(nodes.values()), edges);
            });
        } catch (Exception e) {
            log.warn("[GraphQueryService] 子图查询失败: name={}, error={}", name, e.getMessage(), e);
            return GraphViewDTO.empty();
        }
    }
}
