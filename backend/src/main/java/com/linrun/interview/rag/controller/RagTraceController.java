package com.linrun.interview.rag.controller;

import com.linrun.interview.auth.security.UserContext;
import com.linrun.interview.common.result.Result;
import com.linrun.interview.rag.model.RagTraceDetail;
import com.linrun.interview.rag.service.RagTraceRecorder;
import com.linrun.interview.rag.model.RagTraceRunEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 阶段化 RAG Trace 查询接口，供管理页、Agent 回放和回归分析使用。 */
@RestController
@RequestMapping("/api/v1/rag/traces")
@RequiredArgsConstructor
@Tag(name = "RAG Trace")
public class RagTraceController {

    private final RagTraceRecorder traceRecorder;

    @GetMapping
    @Operation(summary = "查询最近 RAG Trace")
    public Result<List<RagTraceRunEntity>> list(
        @RequestParam(defaultValue = "20") int limit) {
        return Result.success(traceRecorder.list(UserContext.requireUserId(), limit));
    }

    @GetMapping("/{traceId}")
    @Operation(summary = "查询 RAG Trace 详情")
    public Result<RagTraceDetail> detail(@PathVariable String traceId) {
        RagTraceDetail detail = traceRecorder.get(traceId, UserContext.requireUserId());
        if (detail == null) {
            return Result.error("Trace 不存在");
        }
        return Result.success(detail);
    }
}
