# RAG 评测集审核规范

这份文档把 Hollis 的 RAG 评测资料落成项目里的审核规则。核心原则是：

> 生成评测不是“给每个问题配一段看起来合理的答案”，而是要同时固定问题、标准答案、
> 标准证据和样本来源；被测系统还必须回传它实际生成的回答与实际召回上下文。

## 当前仓库的真实状态

| 文件 | 当前用途 | 是否可作为正式 gold |
|---|---|---|
| `eval/rag-retrieval/eval-dataset.yaml` | 80 题检索开发回归集；用 `key_points` 做词法代理 | 否。没有 reference answer、gold evidence、人工审核和 holdout |
| `eval/ragas/generation-dataset.yaml` | 20 题 RAGAS smoke 集 | 否。答案标为 `assistant_draft`/`draft`，仅验证链路和格式 |
| `eval/ragas/generation-dataset-100.yaml` | 100 题候选正式集：每域 16 dev + 4 holdout，90 可答 + 10 无答案 | 暂否。答案标为 `assistant_curated_pending_human_review`，需要人工逐题确认 |

运行 `python ../scripts/audit_rag_dataset.py --dataset generation-dataset-100.yaml --require-reviewed-gold`
应该在审核完成前失败。这个失败是保护措施，不是脚本故障。

## 一条样本至少要固定什么

正式生成集中的可回答题，建议包含下面字段：

```yaml
- id: mysql-001
  split: dev                 # dev / holdout；holdout 不参与调参
  origin: real_log            # real_log / manual / llm_paraphrase / adversarial
  source: mysql
  question_type: multi_hop    # fact / concept / procedure / comparison / multi_hop / unanswerable
  question: ...
  query_variants:
    - ...                     # 口语化、错别字、代码中英混写等，可选
  answerable: true
  expected_refusal: false
  reference_answer: ...       # 人工审核后的完整答案，不由被测 RAG 生成
  key_points:                 # 原子事实，不要把多个方案混成一个“同义词组”
    - [“...”]
  gold_evidence:
    - doc_id: mysql-manual
      section: "事务/MVCC"
      quote: "..."
  review_status: human_reviewed
  reviewer: ...
  corpus_version: ...
```

`gold_evidence` 可以是稳定的文档 ID + 章节 + 证据短句。不要把当前 chunk 编号当永久真值，
因为切片参数变化会导致 chunk ID 改变；运行时的 `retrieved_contexts` 则必须来自本次真实检索。

## 人工审核清单

每道题在从 `draft` 改成 `human_reviewed` 前，逐项确认：

1. 问题确实是目标用户会问的，不是把标题改成问号；题意只有一个合理解释。
2. `answerable` 与当前知识库版本一致；知识库没有答案的题标成 `false` 或 `partial`，不要硬写答案。
3. `reference_answer` 只使用知识库可支持的事实，覆盖问题要求的全部原子要点，不把常识/模型记忆偷偷补进去。
4. `gold_evidence` 能逐条支撑 reference answer；若需要跨文档，明确列出全部文档/章节。
5. 关键点是原子事实或必要步骤；“本地消息表 / MQ / Seata”这类不同层次的方案不能放在一个“同义词组”里。
6. 题型、难度、来源、语气和语料版本填写正确；记录审核人和审核时间。
7. 至少抽查标准问法、口语化问法、错别字/中英混写和干扰信息问法，确认没有把答案术语全部泄漏到问题中。

## 数据集分层

- **smoke：10–20 题**，只验证导出、RAGAS 配置和报告格式；可以是草案，但报告必须标记 draft。
- **dev：50–100 题**，日常调 chunk、topK、混合检索和 rerank；答案与证据需要人工审核。
- **holdout：20–30 题起步**，冻结后不参与调参，只在候选方案确定后运行。
- **refusal/partial：单独统计**，包含知识库无答案、只有部分证据、完全无关问题；不要把它们混进普通 `context_recall` 平均值。

题型不要只按 `fact/concept/synthesis` 三个标签凑数。最终集至少应覆盖事实、解释、步骤、
对比/取舍、多跳综合、真实口语化和无答案/部分覆盖。真实日志、FAQ、失败案例优先；LLM
扩写只能增加表达变体，不能替代人工标注。

## 指标与数据字段的关系

对 `answerable=true` 的正式题，RAGAS 主评测使用：

`question + response + retrieved_contexts + reference`

其中 `response` 和 `retrieved_contexts` 必须由当前 RAG 一次调用返回；`reference` 是人工审核的
标准答案。四个核心指标是 Faithfulness、Answer Relevancy、Context Precision、Context Recall。
AnswerCorrectness 只作为显式诊断，不用草案答案或关键词代理冒充正式分数。

无答案题不评普通 Context Recall/AnswerCorrectness，而看拒答召回率和无答案幻觉率，并抽查
“部分有证据时回答有据部分、缺失部分明确说明”的分层拒答行为。

## 本次完整链路运行记录（2026-08-11）

这 100 题已经用项目当前运行中的完整链路执行过一次：查询重写、路由、Elasticsearch/混合检索、父文档扩展、BGE 重排、CRAG 校验以及生成均走真实服务；明确的图问题走 Neo4j/Text2Cypher，结构化查询走 Text2SQL，表格问题走上传的 Excel 知识库。运行使用 userId=4 的 6 个已完成向量化知识库（KB 6–11），并建立了 Neo4j 的 18 个实体、24 条 `RELATES_TO` 关系。

- 100 题真实问答与召回上下文：[qa-export-20260811-023812.jsonl](.work/qa-export-20260811-023812.jsonl)
- 100 题 RAGAS 明细：[ragas-cases-20260811-032052.jsonl](.work/ragas-cases-20260811-032052.jsonl)
- 100 题报告：[ragas-report-20260811-032052.md](.work/ragas-report-20260811-032052.md)
- 图数据库、Text2SQL、Excel 路由专项 9 题报告：[ragas-report-20260811-023625.md](.work/ragas-report-20260811-023625.md)

100 题中有 90 题 `answerable=true`，四个 RAGAS 指标只对这 90 题计算；另外 10 题单独计算拒答诊断。当前草案运行的结果为：Faithfulness **0.8924**、Answer Relevancy **0.8520**、Context Precision **0.8585**、Context Recall **0.7992**；拒答召回率 **1.0000**，无答案幻觉率 **0.0000**。工程延迟为 p50 **12.443 s**、p95 **16.500 s**、最大 **20.596 s**。专项路由 9 题的 graph/relational/knowledge-base 路由计数为 **4/3/2**，Context Precision 与 Context Recall 均为 **1.0000**。

这些数值可以证明“链路可运行且路由/拒答行为可观测”，但不能直接当成正式模型对比或简历指标：报告明确标记为 `reference_answer_draft`，而且当前 `review_status` 仍是草案。正式报告前必须把每个 `reference_answer`、`key_points` 和 `gold_evidence` 逐条对照知识库审核，再冻结 holdout 集。

本轮明细中优先需要人工复核的低覆盖样本如下。它们不一定代表代码故障，也可能是标准答案写入了知识库没有明确给出的通用经验；审核时应以“标准答案能否被 gold evidence 逐条支撑”为准：

| 样本 | 当前问题 | 复核重点 |
|---|---|---|
| `dist-004` | MySQL 分布式锁 | 删除 `SELECT ... FOR UPDATE`、超时等未被证据明确支持的扩展，或补充对应原文证据 |
| `dist-005` | ZooKeeper 分布式锁 | 只保留临时顺序节点、最小节点、监听前驱和会话释放等原文事实 |
| `mysql-015` | 分库分表的问题 | 逐条确认跨库事务、跨分片 JOIN、全局 ID、分页、迁移等要点是否都有证据 |
| `spring-018` | SpringTask 重复执行 | 区分“知识库明确说明的集群重复执行风险”和外部框架建议，避免把 Quartz/XXL-JOB 当成原文事实 |
| `redis-018` | 热 Key/大 Key 治理 | 将热 Key 与大 Key 的风险、拆分/本地缓存/异步删除分别绑定到对应证据 |

审核完成后，将上述样本的 `review_status` 改为 `human_reviewed`，补齐 `reviewer`、`reviewed_at`、`corpus_version` 和逐条 `gold_evidence`，然后不再使用 `--allow-draft-reference` 重跑同一命令；只有那次结果才适合用于正式对比或简历表述。
