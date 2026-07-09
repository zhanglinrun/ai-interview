package com.linrun.interview.modules.knowledgebase;

import com.linrun.interview.common.annotation.RateLimit;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.modules.knowledgebase.model.GraphViewDTO;
import com.linrun.interview.modules.knowledgebase.service.GraphQueryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 技能/知识图谱可视化只读端点。
 *
 * <p>Neo4j 未启用时优雅返回空图（{@code nodes/edges} 为空），前端页面渲染空状态。
 */
@RestController
@RequestMapping("/api/knowledgebase/graph")
@RequiredArgsConstructor
@Tag(name = "知识图谱", description = "技能/知识图谱可视化查询")
public class GraphViewController {

    private final GraphQueryService graphQueryService;

    /** 技能图谱概览（Skill-[:COVERS]->Concept）。 */
    @GetMapping("/overview")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 30)
    public Result<GraphViewDTO> overview(@RequestParam(defaultValue = "120") int limit) {
        return Result.success(graphQueryService.overview(limit));
    }

    /** 以某个概念/技能节点为中心的一跳子图。 */
    @GetMapping("/neighbors")
    @RateLimit(dimension = RateLimit.Dimension.USER, count = 30)
    public Result<GraphViewDTO> neighbors(@RequestParam String name,
                                          @RequestParam(defaultValue = "60") int limit) {
        return Result.success(graphQueryService.neighbors(name, limit));
    }
}
