package com.linrun.interview.modules.knowledgebase.model;

import java.util.List;

/**
 * 图谱可视化数据（技能/知识图谱前端展示用）。
 *
 * <p>节点 id 采用 {@code <label>:<name>}（如 {@code Skill:Java后端}），避免同名不同类节点冲突；
 * 边为无向展示（前端力导向/环形布局），{@code type} 记录关系类型（COVERS / BELONGS_TO / RELATES_TO）。
 */
public record GraphViewDTO(
    List<Node> nodes,
    List<Edge> edges
) {

    public record Node(String id, String label, String type) {}

    public record Edge(String source, String target, String type) {}

    public static GraphViewDTO empty() {
        return new GraphViewDTO(List.of(), List.of());
    }
}
