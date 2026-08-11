# RAG 评测对比与 STAR 项目介绍

这份文档用于把 RAG 优化结果转换成可复现、可解释的项目经历。所有数字都能回指到仓库中的 JSONL 与 Markdown 报告。

## 一、优化前后对比

### 对比口径

- **历史基线**：100 题主集，90 道可回答题参与四个 RAGAS 指标，10 道拒答题单独统计；报告生成于 2026-08-11 01:51:13。
- **当前版本**：同一批 100 个题目（仅 `dist-006` 的标准答案草案曾做过一次证据收窄），同一 qwen-plus / text-embedding-v3 评测配置；报告生成于 2026-08-11 03:37:40。
- 两次都走真实 RAG 生成导出，不是拿离线答案直接评分。RAGAS Judge 存在随机性，因此下面的单次差值是工程回归证据，不应包装成严格的统计显著性结论。

| 指标 | 优化前 | 优化后 | 变化 | 解释 |
|---|---:|---:|---:|---|
| Faithfulness | 0.8975 | 0.8924 | -0.51 pp | 仍稳定在 89%+；单次 Judge 波动，不能宣称该项提升 |
| Answer Relevancy | 0.8512 | 0.8520 | +0.08 pp | 基本持平 |
| Context Precision | 0.8300 | 0.8585 | **+2.85 pp** | 相关证据排序更集中 |
| Context Recall | 0.7886 | 0.7992 | **+1.06 pp** | 标准答案所需证据覆盖略有提升 |
| Refusal Recall | 0.7000 | 1.0000 | **+30.00 pp** | 10 道无答案题全部正确拒答 |
| 无答案幻觉率 | 0.3000 | 0.0000 | **-30.00 pp** | 不再把未来版本/动态事实编造成答案 |
| p50 latency | 12,854 ms | 12,443 ms | -411 ms | 约下降 3.2% |
| p95 latency | 17,631 ms | 16,500 ms | -1,131 ms | 约下降 6.4% |

原始证据：

- 基线：[ragas-report-20260811-013420.md](../eval/ragas/.work/ragas-report-20260811-013420.md)、[qa-export-20260811-010941.jsonl](../eval/ragas/.work/qa-export-20260811-010941.jsonl)
- 当前：[ragas-report-20260811-032052.md](../eval/ragas/.work/ragas-report-20260811-032052.md)、[qa-export-20260811-023812.jsonl](../eval/ragas/.work/qa-export-20260811-023812.jsonl)

### 路由专项结果

9 道专门覆盖 GraphRAG、Text2SQL、Excel 的测试最终路由为：`graph_db=4`、`relational_db=3`、`knowledge_base=2`；Context Precision 与 Context Recall 均为 **1.0000**。这组题的题面在调试过程中做过证据收窄，因此只作为“多数据源能力已打通”的专项结果，不与早期 9 题报告直接做同集对比。

## 二、实际做的优化

1. 将父文档扩展从“替换命中子块”改为“保留命中子块并追加父上下文”，避免精确证据被父块摘要覆盖。
2. 为 Text2SQL 增加只读单语句校验、用户数据范围校验、表白名单和安全降级，并在 Prompt 中明确 `documents` 与业务表的选择规则。
3. 为显式 Neo4j/关系问题增加确定性路由和只读 Text2Cypher 模板，减少图问题误路由到 Elasticsearch。
4. 评测入口保留查询改写、混合检索、RRF、BGE Rerank、父子上下文和 CRAG，但关闭查询分解，保证评测问题与生产问法一一对应。
5. 导出 `route_source`、`route_intent`、`route_confidence`、`route_reasoning`，让每道题都能解释“为什么走这条链路”。
6. 将 100 题拆成 90 道可回答题和 10 道拒答题；拒答题不混入 Context Recall，单独统计拒答召回率和无答案幻觉率。

## 三、STAR 项目介绍

### S：Situation

企业内部的技术文档、历史项目资料和规范分散在 PDF、Markdown、Excel 等文件中，传统关键词搜索难以处理同义表达、跨文档问题和结构化关系查询，成员需要反复询问，且回答缺少证据边界。

### T：Task

负责搭建可追溯的 RAG 知识库问答链路：支持多格式解析、父子切片、混合检索、重排、引用校验，并让文档问题、图关系问题、SQL 统计问题和表格问题分别进入合适的数据源；同时建立可复现的 100 题 RAGAS 评测集，量化优化效果并控制无答案幻觉。

### A：Action

- 使用 MinerU/Tika 完成 PDF、DOC、Excel、Markdown 解析，采用父子切片与向量化入库。
- 基于 Elasticsearch 实现 BM25 + 向量检索 + RRF 融合，接入 BGE Reranker，并保留命中子块后追加父上下文。
- 使用意图识别和 Multi-Source Router：文档问题走知识库，关系问题走 Neo4j/Text2Cypher，统计问题走受限 Text2SQL，Excel 关系数据作为结构化知识库验证源。
- 加入用户隔离、只读 SQL、表白名单、引用编号和拒答闸门，避免跨用户数据泄露和无依据生成。
- 按 Hollis 的“问题—标准答案—标准证据—真实召回上下文”方法构建 100 题候选集，用 RAGAS 评估 Faithfulness、Answer Relevancy、Context Precision、Context Recall，并额外统计拒答质量和延迟。

### R：Result

在同一 100 题回归集上，Context Precision 从 **83.00% 提升至 85.85%**，Context Recall 从 **78.86% 提升至 79.92%**；拒答召回率从 **70% 提升至 100%**，无答案幻觉率从 **30% 降至 0%**；p95 延迟从 **17.631 s 降至 16.500 s**。GraphRAG、Text2SQL、Excel 专项 9 题均完成正确路由，Context Precision/Recall 达到 **100%**。

## 四、简历表述（可直接改写）

> **RAG 知识库问答平台**：针对企业技术文档分散、跨格式检索困难和无答案场景易产生幻觉的问题，负责证据化 RAG 核心链路开发。基于 MinerU/Tika 完成 PDF/DOC/Excel/Markdown 解析，采用父子切片、BM25+向量+RRF 混合检索、BGE Rerank 和父上下文扩展；设计 Multi-Source Router，将文档问答、Neo4j 图关系查询、只读 Text2SQL 和 Excel 结构化数据分流。构建 100 题 RAGAS 评测集并导出真实回答、召回上下文和路由 Trace；同集回归中 Context Precision 由 83.00% 提升至 85.85%，Context Recall 由 78.86% 提升至 79.92%，拒答召回率由 70% 提升至 100%，无答案幻觉率降至 0%。

正式对外使用前，需先把 `generation-dataset-100.yaml` 中的 `review_status` 从草案改为人工审核状态，并冻结 holdout 集；当前数字用于工程回归和面试讲解，不应伪装成已完成统计显著性验证的线上指标。
