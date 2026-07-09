import { request } from './request';

export interface GraphNode {
  id: string;
  label: string;
  type: string;
}

export interface GraphEdge {
  source: string;
  target: string;
  type: string;
}

export interface GraphView {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export const graphApi = {
  /** 技能图谱概览（Skill-[:COVERS]->Concept）。 */
  overview(limit = 120): Promise<GraphView> {
    return request.get<GraphView>(`/api/knowledgebase/graph/overview?limit=${limit}`);
  },

  /** 以某个概念/技能节点为中心的一跳子图。 */
  neighbors(name: string, limit = 60): Promise<GraphView> {
    return request.get<GraphView>(
      `/api/knowledgebase/graph/neighbors?name=${encodeURIComponent(name)}&limit=${limit}`
    );
  },
};
