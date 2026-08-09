package com.linrun.interview.document.job;

import com.linrun.interview.common.annotation.DistributeLock;
import com.linrun.interview.document.service.Neo4jDomainKnowledgeGraphService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Neo4j 领域图谱补偿：应用重启或 Neo4j 短暂不可用时，
 * 幂等重放平台技术实体和关系种子。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Neo4jKnowledgeGraphCompensationJob {

    private final Neo4jDomainKnowledgeGraphService graphService;

    @Scheduled(
        fixedDelayString = "${app.knowledgebase.compensation.neo4j-delay-ms:900000}",
        initialDelayString = "${app.knowledgebase.compensation.neo4j-initial-delay-ms:20000}")
    @XxlJob("ragNeo4jGraphCompensation")
    @DistributeLock(key = "'kb:compensation:neo4j-graph'", waitTime = 0, leaseTime = 600,
        message = "Neo4j 领域图谱补偿任务已在其他实例执行")
    public void run() {
        if (!graphService.available()) {
            return;
        }
        try {
            boolean synced = graphService.seed();
            log.info("Neo4j 领域图谱补偿完成: synced={}", synced);
        } catch (Exception e) {
            log.warn("Neo4j 领域图谱补偿失败，等待下一轮重试: {}", e.getMessage(), e);
        }
    }
}
