package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.UserContext;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository.KeywordHit;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 知识库向量存储服务
 * 负责文档分块、向量化和检索（向量通道 + 关键词通道的混合检索）。
 */
@Slf4j
@Service
public class KnowledgeBaseVectorService {
    
    /**
     * 阿里云 DashScope Embedding API 批量大小限制
     */
    private static final int MAX_BATCH_SIZE = 10;
    private final VectorStore vectorStore;
    private final KnowledgeBaseChunkingService chunkingService;
    private final VectorRepository vectorRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBaseQueryProperties queryProperties;
    private final KnowledgeBaseVectorizeProperties vectorizeProperties;
    private final MeterRegistry meterRegistry;

    /**
     * 全局 embedding 并发信号量：限制同一时刻向 DashScope 发起的批次调用数，
     * 避免批量上传时一次性打爆第三方配额、误伤在线请求。
     */
    private final Semaphore embeddingPermits;
    /** 当前在飞的 embedding 调用数，注册成 gauge 供监控观察并发水位。 */
    private final AtomicInteger inFlightEmbeddings = new AtomicInteger(0);

    /**
     * 分块级并行线程池：单个大文档内的多个 embedding 批次并发提交，
     * 让全局信号量的许可被同一文档吃满，专门压低"单个大文档"这条关键路径。
     * 为 null 表示分块并行未启用（chunkParallelism <= 1），走历史的串行分批。
     */
    private final ExecutorService chunkExecutor;

    public KnowledgeBaseVectorService(VectorStore vectorStore,
                                      KnowledgeBaseChunkingService chunkingService,
                                      VectorRepository vectorRepository,
                                      KnowledgeBaseRepository knowledgeBaseRepository,
                                      KnowledgeBaseQueryProperties queryProperties,
                                      KnowledgeBaseVectorizeProperties vectorizeProperties,
                                      @Autowired(required = false) MeterRegistry meterRegistry) {
        this.vectorStore = vectorStore;
        this.chunkingService = chunkingService;
        this.vectorRepository = vectorRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.queryProperties = queryProperties;
        this.vectorizeProperties = vectorizeProperties;
        this.meterRegistry = meterRegistry;
        this.embeddingPermits = new Semaphore(Math.max(1, vectorizeProperties.getEmbeddingConcurrency()), true);
        if (meterRegistry != null) {
            meterRegistry.gauge("app.ai.vectorize.embedding_inflight", inFlightEmbeddings);
        }
        // 分块级并行：仅当配置 > 1 时创建线程池，否则保持单文档内串行分批的历史行为
        int chunkPar = vectorizeProperties.getChunkParallelism();
        if (chunkPar > 1) {
            AtomicInteger seq = new AtomicInteger(0);
            this.chunkExecutor = new ThreadPoolExecutor(
                chunkPar,
                chunkPar,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "vectorize-chunk-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
            );
            log.info("向量化启用分块级并行: chunkParallelism={}", chunkPar);
        } else {
            this.chunkExecutor = null;
        }
    }

    /**
     * 释放分块并行线程池，避免应用关闭时线程泄漏。
     */
    @PreDestroy
    void shutdownChunkExecutor() {
        if (chunkExecutor != null) {
            chunkExecutor.shutdown();
        }
    }

    /**
     * 应用启动后确保关键词检索索引就绪。
     * vector_store 表由 Spring AI 在启动时建好，此时再建 GIN 索引是安全的。
     */
    @PostConstruct
    void initKeywordIndex() {
        if (queryProperties.getHybrid().isEnabled()) {
            vectorRepository.ensureKeywordIndex();
        }
    }

    /**
     * 将知识库内容向量化并存储
     * 注意：此方法不加事务，避免外部 API 调用占用 DB 连接
     * @param knowledgeBaseId 知识库ID
     * @param content 知识库文本内容
     */
    public void vectorizeAndStore(Long knowledgeBaseId, String content) {
        log.info("开始向量化知识库: kbId={}, contentLength={}", knowledgeBaseId, content.length());
        long startNanos = System.nanoTime();
        KnowledgeBaseEntity knowledgeBase = knowledgeBaseRepository.findById(knowledgeBaseId).orElse(null);
        // 进入处理中状态：前端可据此展示“索引中”并轮询进度，取代上传后只能盲等结果
        finalizeVectorization(knowledgeBase, knowledgeBaseId, VectorStatus.PROCESSING, null, null);
        try {

            // 1. 文本分块 + 质量评估：过滤空白/过短噪声 chunk，避免无效 embedding 污染检索
            KnowledgeBaseChunkingService.ChunkingResult chunkingResult = chunkingService.splitWithQuality(content);
            List<Document> chunks = chunkingResult.chunks();
            log.info("文本分块完成: chunks={}, rawChunks={}, filtered={}, documentLength={}, qualityScore={}",
                    chunks.size(), chunkingResult.rawChunkCount(), chunkingResult.filteredChunkCount(),
                    chunkingResult.documentLength(), chunkingResult.qualityScore());

            // 2. 为每个 chunk 填充检索/过滤/来源 metadata，并计算内容哈希用于去重与增量
            enrichChunkMetadata(chunks, knowledgeBaseId, knowledgeBase);

            // 3. 同文档内按内容哈希去重（重复段落只留首个），再去重后重新编号 chunk_index/chunk_count
            List<Document> dedupedChunks = dedupChunksByHash(chunks);
            renumberChunks(dedupedChunks);

            // 4. 增量 diff：对比已入库 chunk 哈希，只处理 delta，不再全量删重建
            Set<String> existingHashes = vectorRepository.findChunkHashesByKbId(knowledgeBaseId);
            Set<String> targetHashes = dedupedChunks.stream()
                    .map(this::chunkHashOf)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            List<Document> toInsert = dedupedChunks.stream()
                    .filter(chunk -> !existingHashes.contains(chunkHashOf(chunk)))
                    .collect(Collectors.toList());
            List<Document> toUpdateMetadata = dedupedChunks.stream()
                    .filter(chunk -> existingHashes.contains(chunkHashOf(chunk)))
                    .collect(Collectors.toList());
            Set<String> staleHashes = existingHashes.stream()
                    .filter(hash -> !targetHashes.contains(hash))
                    .collect(Collectors.toSet());

            log.info("增量向量化: 已有={}, 目标={}, 待嵌入={}, 待更新元数据={}, 失效={}",
                    existingHashes.size(), targetHashes.size(), toInsert.size(),
                    toUpdateMetadata.size(), staleHashes.size());

            // 5. 删除失效 chunk（旧 split 有、新 split 没有的内容）
            vectorRepository.deleteHashlessChunksByKbId(knowledgeBaseId);
            if (!staleHashes.isEmpty()) {
                vectorRepository.deleteByKbIdAndChunkHashes(knowledgeBaseId, staleHashes);
            }

            // 6. 内容未变的 chunk 复用旧向量，但必须刷新来源、分片序号等 metadata
            for (Document chunk : toUpdateMetadata) {
                vectorRepository.updateMetadataByKbIdAndChunkHash(
                    knowledgeBaseId, chunkHashOf(chunk), chunk.getMetadata());
            }

            // 7. 仅对新增/变更 chunk 做向量化并入库；内容未变的 chunk 复用旧向量，跳过 embedding
            if (!toInsert.isEmpty()) {
                embedInBatches(toInsert);
            } else {
                log.info("无新增/变更 chunk，跳过向量化: kbId={}", knowledgeBaseId);
            }

            log.info("知识库向量化完成: kbId={}, 目标={}, 嵌入={}",
                    knowledgeBaseId, targetHashes.size(), toInsert.size());
            finalizeVectorization(knowledgeBase, knowledgeBaseId, VectorStatus.COMPLETED, null, targetHashes.size());
            recordVectorizeMetrics(true, startNanos);
        } catch (Exception e) {
            finalizeVectorization(knowledgeBase, knowledgeBaseId, VectorStatus.FAILED, e.getMessage(), 0);
            recordVectorizeMetrics(false, startNanos);
            log.error("向量化知识库失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "向量化知识库失败: " + e.getMessage(), e);
        }
    }

    private void enrichChunkMetadata(List<Document> chunks, Long knowledgeBaseId,
                                     KnowledgeBaseEntity knowledgeBase) {
        for (Document chunk : chunks) {
            Map<String, Object> metadata = chunk.getMetadata();
            metadata.put("kb_id", knowledgeBaseId.toString());
            // 内容哈希作为 chunk 的稳定标识，用于去重与增量；chunk_index/chunk_count 在去重后重新编号
            metadata.put("chunk_hash", ChunkContentHasher.hash(chunk.getText()));
            if (knowledgeBase != null) {
                putIfNotBlank(metadata, "source_name", knowledgeBase.getOriginalFilename());
                String documentTitle = knowledgeBase.getName();
                putIfNotBlank(metadata, "document_title", documentTitle);
                putIfNotBlank(metadata, "category", knowledgeBase.getCategory());
                // 父子 chunk：同文档同章节标识，供检索侧 small-to-big 聚合更大上下文
                Object sectionTitle = metadata.get("section_title");
                if (documentTitle != null && !documentTitle.isBlank()
                        && sectionTitle != null && !sectionTitle.toString().isBlank()) {
                    metadata.put("parent_section", documentTitle + "|" + sectionTitle);
                }
                if (knowledgeBase.getUserId() != null) {
                    metadata.put("user_id", knowledgeBase.getUserId().toString());
                }
            }
        }
    }

    /**
     * 同文档内按内容哈希去重：重复内容只保留首个 chunk。
     */
    private List<Document> dedupChunksByHash(List<Document> chunks) {
        Map<String, Document> seenByHash = new LinkedHashMap<>();
        for (Document chunk : chunks) {
            String hash = chunkHashOf(chunk);
            if (hash == null || hash.isBlank()) {
                continue;
            }
            seenByHash.putIfAbsent(hash, chunk);
        }
        return new ArrayList<>(seenByHash.values());
    }

    /**
     * 去重后重新编号 chunk_index/chunk_count，保证展示与计数连续。
     */
    private void renumberChunks(List<Document> chunks) {
        int total = chunks.size();
        for (int i = 0; i < total; i++) {
            Map<String, Object> metadata = chunks.get(i).getMetadata();
            metadata.put("chunk_index", String.valueOf(i));
            metadata.put("chunk_count", String.valueOf(total));
        }
    }

    private String chunkHashOf(Document chunk) {
        Object hash = chunk.getMetadata().get("chunk_hash");
        return hash == null ? null : hash.toString();
    }

    /**
     * 分批向量化并入库：外部 embedding API 调用，不在事务内。
     * 启用分块并行时，单文档内多个批次并发提交，压低单大文档的关键路径。
     */
    private void embedInBatches(List<Document> chunks) {
        int totalChunks = chunks.size();
        int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE;
        log.info("开始分批向量化: 总共 {} 个chunks，分 {} 批处理，每批最多 {} 个",
                totalChunks, batchCount, MAX_BATCH_SIZE);

        List<List<Document>> batches = new ArrayList<>(batchCount);
        for (int i = 0; i < batchCount; i++) {
            int start = i * MAX_BATCH_SIZE;
            int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
            batches.add(chunks.subList(start, end));
        }

        // 分块并行：单文档内的多个批次并发提交
        if (chunkExecutor != null && batchCount > 1) {
            addBatchesInParallel(batches);
        } else {
            for (List<Document> batch : batches) {
                addBatchWithPermit(batch);
            }
        }
    }

    private void putIfNotBlank(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value);
        }
    }

    /**
     * 收尾向量化状态：写入状态机（PROCESSING / COMPLETED / FAILED），可选更新 chunkCount 与错误信息。
     *
     * <p>状态机取代了原先只在成功时写一次 chunkCount 的做法——现在上传后即可被前端轮询，
     * 区分“索引中 / 完成 / 失败”，失败时附带错误信息便于排查。状态写入本身失败不影响主流程，仅告警。
     *
     * @param knowledgeBase   调用方已加载的实体，为 null 时按 id 回查（容错）
     * @param knowledgeBaseId 知识库ID
     * @param status          目标状态
     * @param error           失败原因（仅 FAILED 时写入，会被截断到列长度内）
     * @param chunkCount      非 null 时同步更新分块总数（COMPLETED 写入目标值，FAILED 重置为 0）
     */
    private void finalizeVectorization(KnowledgeBaseEntity knowledgeBase, Long knowledgeBaseId,
                                       VectorStatus status, String error, Integer chunkCount) {
        try {
            KnowledgeBaseEntity target = knowledgeBase != null
                ? knowledgeBase
                : knowledgeBaseRepository.findById(knowledgeBaseId).orElse(null);
            if (target == null) {
                return;
            }
            target.setVectorStatus(status);
            if (chunkCount != null) {
                target.setChunkCount(chunkCount);
            }
            if (status == VectorStatus.COMPLETED) {
                target.setVectorError(null);
            } else if (error != null) {
                target.setVectorError(truncateError(error));
            }
            knowledgeBaseRepository.save(target);
        } catch (Exception e) {
            log.warn("更新向量化状态失败: kbId={}, status={}, error={}",
                knowledgeBaseId, status, e.getMessage());
        }
    }

    /**
     * 截断向量化错误信息，避免超过 vectorError 列长度（500）。
     */
    private String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 490 ? error.substring(0, 490) : error;
    }

    /**
     * 记录单个文档的向量化指标：完成计数（按成功/失败打标签）与单文档耗时。
     * 批量入库时，结合任务数即可算出整体吞吐。
     */
    private void recordVectorizeMetrics(boolean success, long startNanos) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter("app.ai.vectorize.documents",
            Tags.of("status", success ? "success" : "failed")).increment();
        meterRegistry.timer("app.ai.vectorize.document_latency")
            .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 在全局信号量保护下执行一批 embedding 写入。
     * 限制同一时刻在飞的批次调用数，避免批量并行向量化打爆 DashScope 配额。
     * 获取许可超时则放弃保护、直接执行，避免任务被永久阻塞。
     */
    private void addBatchWithPermit(List<Document> batch) {
        boolean acquired = false;
        try {
            acquired = embeddingPermits.tryAcquire(
                vectorizeProperties.getPermitWaitSeconds(), TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("获取 embedding 许可超时，跳过并发保护直接执行本批");
            }
            inFlightEmbeddings.incrementAndGet();
            vectorStore.add(batch);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "向量化被中断");
        } finally {
            inFlightEmbeddings.decrementAndGet();
            if (acquired) {
                embeddingPermits.release();
            }
        }
    }

    /**
     * 并行提交多个批次的 embedding 写入，等待所有批次完成。
     * 使用 CompletableFuture 在 chunkExecutor 线程池中并发执行，每个任务仍受全局信号量保护。
     * 任一批次失败会收集异常并抛出，已成功的批次不回滚（依赖消费者失败重试保证最终一致）。
     */
    private void addBatchesInParallel(List<List<Document>> batches) {
        List<CompletableFuture<Void>> futures = batches.stream()
            .map(batch -> CompletableFuture.runAsync(() -> addBatchWithPermit(batch), chunkExecutor))
            .collect(Collectors.toList());

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "分块并行向量化被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof BusinessException) {
                throw (BusinessException) cause;
            }
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "分块并行向量化失败: " + cause.getMessage());
        }
    }
    
    /**
     * Small-to-big：把命中 chunk 的同段兄弟文本聚合成更大上下文，喂给 LLM 用。
     * 检索仍用小 chunk 精准命中，这里只扩展上下文，不改变命中结果与来源列表。
     * 无 parent_section、查询失败或无兄弟时原样返回命中 chunk 文本。
     *
     * @param doc         命中的小 chunk
     * @param maxChars    扩展后总字符上限
     * @param maxSiblings 最多聚合的兄弟 chunk 数
     * @return 扩展后的上下文文本
     */
    public String expandChunkWithSiblings(Document doc, int maxChars, int maxSiblings) {
        if (doc == null || doc.getText() == null) {
            return "";
        }
        String baseText = doc.getText();
        Map<String, Object> metadata = doc.getMetadata();
        Object kbIdRaw = metadata == null ? null : metadata.get("kb_id");
        Object parentSectionRaw = metadata == null ? null : metadata.get("parent_section");
        if (kbIdRaw == null || parentSectionRaw == null || parentSectionRaw.toString().isBlank()) {
            return baseText;
        }
        Long kbId;
        try {
            kbId = Long.parseLong(kbIdRaw.toString());
        } catch (NumberFormatException e) {
            return baseText;
        }
        List<String> siblings = vectorRepository.findSiblingChunkTexts(
            kbId, parentSectionRaw.toString(), Math.max(maxSiblings, 1), currentUserId());
        return ParentContextExpander.expand(baseText, siblings, Math.max(maxChars, 1));
    }

    /**
     * 基于多个知识库进行相似度搜索（纯向量通道）。
     * 
     * @param query 查询文本
     * @param knowledgeBaseIds 知识库ID列表（如果为空则搜索所有）
     * @param topK 返回top K个结果
     * @return 相关文档列表
     */
    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        log.info("向量相似度搜索: query={}, kbIds={}, topK={}, minScore={}",
            query, knowledgeBaseIds, topK, minScore);
        
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK, 1));

            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            if (results == null) {
                return List.of();
            }

            // Apply topK limiting in case VectorStore returns more than requested
            List<Document> limitedResults = results.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("搜索完成: 找到 {} 个相关文档", limitedResults.size());
            return limitedResults;
            
        } catch (Exception e) {
            log.warn("向量搜索前置过滤失败，回退到本地过滤: {}", e.getMessage(), e);
            return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore);
        }
    }

    /**
     * 混合检索：向量通道 + 关键词通道（pg_trgm），用 RRF 融合两路排名。
     * <p>
     * 若混合检索未启用，直接退回纯向量检索。融合后取 fusionTopK 个候选，
     * 通常再交给重排层做精排。
     *
     * @param query            查询文本
     * @param knowledgeBaseIds 限定知识库
     * @param vectorTopK       向量通道召回数
     * @param minScore         向量通道最低相似度
     * @return 融合排序后的候选文档（score 为向量通道的相似度，便于展示）
     */
    public List<Document> hybridSearch(String query, List<Long> knowledgeBaseIds,
                                       int vectorTopK, double minScore) {
        KnowledgeBaseQueryProperties.Hybrid hybrid = queryProperties.getHybrid();
        if (!hybrid.isEnabled()) {
            return similaritySearch(query, knowledgeBaseIds, vectorTopK, minScore);
        }

        long startNanos = System.nanoTime();
        List<Document> vectorHits = similaritySearch(query, knowledgeBaseIds, vectorTopK, minScore);
        List<KeywordHit> keywordHits = vectorRepository.keywordSearch(
            query, knowledgeBaseIds, hybrid.getKeywordTopK(), hybrid.getKeywordMinSimilarity(), currentUserId());

        List<Document> result;
        if (keywordHits.isEmpty()) {
            // 关键词通道无命中（或不可用），退回纯向量结果
            log.info("混合检索: 关键词通道无命中，使用向量结果 {} 条", vectorHits.size());
            result = vectorHits.stream().limit(hybrid.getFusionTopK()).collect(Collectors.toList());
        } else {
            result = fuseWithRrf(vectorHits, keywordHits, hybrid.getRrfK(), hybrid.getFusionTopK());
            log.info("混合检索融合完成: 向量 {} 条 + 关键词 {} 条 -> 融合 {} 条",
                vectorHits.size(), keywordHits.size(), result.size());
        }

        recordRetrievalMetrics(vectorHits.size(), keywordHits.size(), result.size(), startNanos);
        return result;
    }

    /**
     * 记录混合检索指标：命中率、各通道召回数、端到端耗时。
     * 命中率以"融合后是否有结果"作为有效命中口径，供 Prometheus 采集后算占比。
     */
    private void recordRetrievalMetrics(int vectorRecall, int keywordRecall,
                                        int fusedCount, long startNanos) {
        if (meterRegistry == null) {
            return;
        }
        boolean hit = fusedCount > 0;
        meterRegistry.counter("app.ai.rag.retrieval.requests",
            Tags.of("hit", Boolean.toString(hit))).increment();
        meterRegistry.summary("app.ai.rag.retrieval.vector_recall").record(vectorRecall);
        meterRegistry.summary("app.ai.rag.retrieval.keyword_recall").record(keywordRecall);
        meterRegistry.summary("app.ai.rag.retrieval.fused_count").record(fusedCount);
        meterRegistry.timer("app.ai.rag.retrieval.latency")
            .record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * RRF（Reciprocal Rank Fusion，倒数排名融合）。
     * 对每个通道的命中按排名计算 1/(k+rank) 累加，分高者优先。
     * 以文档正文作为去重键（同一 chunk 可能两路都命中）。
     */
    private List<Document> fuseWithRrf(List<Document> vectorHits,
                                       List<KeywordHit> keywordHits,
                                       int rrfK,
                                       int fusionTopK) {
        Map<String, Document> docByText = new LinkedHashMap<>();
        Map<String, Double> rrfScore = new LinkedHashMap<>();

        for (int rank = 0; rank < vectorHits.size(); rank++) {
            Document doc = vectorHits.get(rank);
            String key = doc.getText();
            if (key == null) {
                continue;
            }
            docByText.putIfAbsent(key, doc);
            rrfScore.merge(key, 1.0 / (rrfK + rank + 1), Double::sum);
        }

        for (int rank = 0; rank < keywordHits.size(); rank++) {
            KeywordHit hit = keywordHits.get(rank);
            String key = hit.content();
            if (key == null) {
                continue;
            }
            docByText.putIfAbsent(key, keywordHitToDocument(hit));
            rrfScore.merge(key, 1.0 / (rrfK + rank + 1), Double::sum);
        }

        return rrfScore.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(Math.max(fusionTopK, 1))
            .map(entry -> docByText.get(entry.getKey()))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    private Document keywordHitToDocument(KeywordHit hit) {
        Map<String, Object> metadata = new HashMap<>(hit.metadata());
        putIfNotBlank(metadata, "kb_id", hit.kbId() != null ? hit.kbId().toString() : null);
        return Document.builder()
            .text(hit.content())
            .metadata(metadata)
            .score(hit.score())
            .build();
    }

    private List<Document> similaritySearchFallback(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        try {
            // 回退检索仍保留 topK/minScore，避免兜底路径引入过多弱相关命中
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK * 3, topK));
            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            List<Document> allResults = vectorStore.similaritySearch(builder.build());
            if (allResults == null || allResults.isEmpty()) {
                return List.of();
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                allResults = allResults.stream()
                    .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds))
                    .collect(Collectors.toList());
            }

            List<Document> results = allResults.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("回退检索完成: 找到 {} 个相关文档", results.size());
            return results;
        } catch (Exception e) {
            log.error("向量搜索失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
                "向量搜索失败: " + e.getMessage(), e);
        }
    }

    private boolean isDocInKnowledgeBases(Document doc, List<Long> knowledgeBaseIds) {
        Object kbId = doc.getMetadata().get("kb_id");
        if (kbId == null) {
            return false;
        }
        try {
            Long kbIdLong = kbId instanceof Long
                ? (Long) kbId
                : Long.parseLong(kbId.toString());
            if (!knowledgeBaseIds.contains(kbIdLong)) {
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        // 第二层 user_id 纵深防御：fallback 路径与向量主路径一致，也校验归属
        Long userId = currentUserId();
        if (userId != null) {
            Object docUserId = doc.getMetadata().get("user_id");
            if (docUserId == null || !userId.toString().equals(docUserId.toString())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构建向量检索 filter 表达式：kb_id 限定 + 可选的 user_id 纵深防御。
     * <p>
     * 能从请求上下文取到当前用户时，在 kb_id 过滤外再叠加 user_id 条件，
     * 形成与 DB 层归属校验独立的第二层隔离；取不到（评测、异步线程等）时
     * 退化为仅 kb_id，兼容历史行为。
     */
    private String buildKbFilterExpression(List<Long> knowledgeBaseIds) {
        String values = knowledgeBaseIds.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .map(id -> "'" + id + "'")
            .collect(Collectors.joining(", "));
        String expression = "kb_id in [" + values + "]";
        Long userId = currentUserId();
        if (userId != null) {
            expression += " && user_id == '" + userId + "'";
        }
        return expression;
    }

    /**
     * 取当前请求用户 ID，用于检索层第二层 user_id 过滤。
     * 返回 null 表示无登录上下文，调用方据此决定是否启用第二层。
     */
    private Long currentUserId() {
        return UserContext.getUserId();
    }
    
    /**
     * 删除指定知识库的所有向量数据
     * 委托给 VectorRepository 处理
     * 
     * @param knowledgeBaseId 知识库ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        try {
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
        } catch (Exception e) {
            log.error("删除向量数据失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            // 不抛出异常，允许继续执行其他删除操作
            // 如果确实需要严格保证，可以取消下面的注释
            // throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败");
        }
    }
}
