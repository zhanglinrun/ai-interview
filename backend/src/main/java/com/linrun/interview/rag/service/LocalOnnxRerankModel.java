package com.linrun.interview.rag.service;
import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;
import com.linrun.interview.rag.service.RerankService;


import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties.Rerank.LocalOnnx;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 本地 ONNX BGE-RERANKER 评分模型。
 *
 * <p>在 Java 进程内通过 ONNX Runtime 跑 BGE-RERANKER 做 RAG 重排，省去云端调用网络延迟与计费。
 * 实现 LC4j {@link ScoringModel}，委托单例 {@link OnnxScoringModel}，供
 * {@link InterviewReRankingContentAggregator} 注入。
 *
 * <p>与早期实现的差异（弃糟粕）：
 * <ul>
 *   <li><b>路径/maxSequenceLength 不硬编码</b>：走 {@link KnowledgeBaseQueryProperties.Rerank.LocalOnnx} 配置</li>
 *   <li><b>加载失败不抛异常中断</b>：{@code getInstance} 失败时记 warn，由 {@link RerankService} 退回 RRF 顺序</li>
 *   <li>保留 {@code resolveClasspathToFilePath} 的 JAR 内复制临时文件逻辑（生产 jar 部署需要）</li>
 *   <li>模型文件不入 git（~400MB），放 {@code src/main/resources/model/bge-reranker-model/}，
 *       由 README 说明从 {@code onnx-community/bge-reranker-v2-m3-ONNX} 下载</li>
 * </ul>
 *
 * <p>本类非 Spring bean（无模型文件时不应让容器启动失败），由 {@link RerankService} 懒加载。
 */
@Slf4j
public class LocalOnnxRerankModel implements ScoringModel {

    /** 单例 OnnxScoringModel，volatile 保证多线程可见性。null 表示未初始化或加载失败。 */
    private static volatile OnnxScoringModel instance;
    private static volatile boolean initFailed = false;

    private final LocalOnnx config;

    public LocalOnnxRerankModel(LocalOnnx config) {
        this.config = config;
    }

    /**
     * 本地 ONNX reranker 是否可用（模型文件存在且首次加载成功）。
     */
    public boolean isAvailable() {
        return getInstance() != null;
    }

    private OnnxScoringModel getInstance() {
        if (instance != null) {
            return instance;
        }
        if (initFailed) {
            return null;
        }
        synchronized (LocalOnnxRerankModel.class) {
            if (instance != null) {
                return instance;
            }
            if (initFailed) {
                return null;
            }
            try {
                String modelPath = resolveClasspathToFilePath(stripClasspathPrefix(config.getModelPath()));
                String tokenizerPath = resolveClasspathToFilePath(stripClasspathPrefix(config.getTokenizerPath()));
                int maxSeq = config.getMaxSequenceLength() > 0 ? config.getMaxSequenceLength() : 8192;
                log.info("初始化本地 ONNX BGE-RERANKER: modelPath={}, tokenizerPath={}, maxSeq={}",
                    modelPath, tokenizerPath, maxSeq);
                instance = new OnnxScoringModel(modelPath, tokenizerPath, maxSeq);
                log.info("本地 ONNX BGE-RERANKER 初始化完成");
                return instance;
            } catch (Throwable e) {
                // 模型文件缺失/加载失败：标记并降级云端，不抛异常中断 RAG
                initFailed = true;
                log.warn("本地 ONNX BGE-RERANKER 加载失败: {}", e.getMessage());
                return null;
            }
        }
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        OnnxScoringModel model = getInstance();
        if (model == null) {
            // 调用方 RerankService 应在路由层判断 isAvailable 后才委托，到这说明降级路径漏判，
            // 返回等分让上层退回原序，避免 NPE
            log.warn("本地 ONNX rerank 不可用，返回等分降级");
            return Response.from(zeroScores(segments == null ? 0 : segments.size()));
        }
        return model.scoreAll(segments, query);
    }

    private List<Double> zeroScores(int size) {
        java.util.List<Double> zeros = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            zeros.add(0.0);
        }
        return zeros;
    }

    /** 去掉 classpath: 前缀，得到纯资源相对路径。 */
    private static String stripClasspathPrefix(String path) {
        if (path == null) {
            return "";
        }
        if (path.startsWith("classpath:")) {
            return path.substring("classpath:".length());
        }
        return path;
    }

    /**
     * 将 classpath 资源解析为文件绝对路径。优先直接取文件路径（IDE/解压目录），
     * 资源在 JAR 内则复制到临时文件后返回临时文件路径（生产 jar 部署需要）。
     */
    private static String resolveClasspathToFilePath(String classpathResource) throws IOException {
        URL resource = LocalOnnxRerankModel.class.getClassLoader().getResource(classpathResource);
        if (resource == null) {
            throw new IOException("classpath 下未找到资源: " + classpathResource
                + "，请确认模型文件已放置到 resources/model/bge-reranker-model/");
        }
        try {
            File file = new File(resource.toURI());
            if (file.exists()) {
                return file.getAbsolutePath();
            }
        } catch (Exception e) {
            log.debug("资源在 JAR 包内，将复制到临时文件: {}", classpathResource);
        }
        try (InputStream is = LocalOnnxRerankModel.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (is == null) {
                throw new IOException("无法读取 classpath 资源: " + classpathResource);
            }
            String suffix = classpathResource.substring(classpathResource.lastIndexOf('.') + 1);
            Path tempFile = Files.createTempFile("bge-reranker-", "." + suffix);
            tempFile.toFile().deleteOnExit();
            Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("已将 classpath 资源复制到临时文件: {}", tempFile.toAbsolutePath());
            return tempFile.toAbsolutePath().toString();
        }
    }
}
