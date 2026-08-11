#!/usr/bin/env python3
"""RAGAS 生成质量评测 runner（P4.3 评测闭环）。

流程：
  1. 读生成评测集 ``eval/ragas/generation-dataset-100.yaml``（20 题 smoke 集和 80 题检索集需显式传 ``--dataset``）；
  2. 只把 ``review_status=human_reviewed/approved`` 的 ``reference_answer`` 当正式标准答案；
     草案必须显式加 ``--allow-draft-reference``，否则自动降级/终止，不能冒充 gold；
     没有标准答案时才显式降级为 ``key_points`` 关键点代理；
  3. 按 ``source`` 映射到知识库 id（环境变量 ``RAGEVAL_KB_*``），跳过未配置的 source；
  4. 调后端 ``POST /api/v1/knowledge-bases/eval/export-qa`` 批量走完整 RAG 生成，拿回
     同一次检索使用的 ``contexts[]``，连同 id/source/difficulty/latency 落 JSONL；
  5. 用 RAGAS 计算四个核心指标：faithfulness / answer_relevancy /
     context_precision / context_recall；AnswerCorrectness 只作为显式开启的可选诊断；
  6. 输出逐题 JSONL、分层均值和可选拒答/延迟诊断到 ``eval/ragas/.work/``。

评测模型走 DashScope OpenAI 兼容端点（``DASHSCOPE_API_KEY``）。

一条命令复现（先 ``uv sync``）：
    export RAGEVAL_SATOKEN=... DASHSCOPE_API_KEY=...
    export RAGEVAL_KB_REDIS=.. RAGEVAL_KB_MYSQL=.. RAGEVAL_KB_DISTRIBUTED=..
    uv run run_ragas.py --limit 20

仅导出不评测：``uv run run_ragas.py --export-only``
用已有 JSONL 评测：``uv run run_ragas.py --from-jsonl .work/qa-export-xxx.jsonl``
"""
from __future__ import annotations

import argparse
import datetime
import json
import os
import pathlib
import sys
from collections import defaultdict, deque
import math
from typing import Any

import requests
import yaml

HERE = pathlib.Path(__file__).resolve().parent
DEFAULT_DATASET = HERE / "generation-dataset-100.yaml"
DEFAULT_OUT = HERE / ".work"
SOURCE_ENV = {
    "redis": "RAGEVAL_KB_REDIS",
    "mysql": "RAGEVAL_KB_MYSQL",
    "distributed": "RAGEVAL_KB_DISTRIBUTED",
    "jvm": "RAGEVAL_KB_JVM",
    "spring": "RAGEVAL_KB_SPRING",
    # 多源路由套件使用结构化关系 Excel 知识库；同一 KB 会按问题意图
    # 进入 Neo4j / MySQL / ES 三条完整检索路径。
    "structured": "RAGEVAL_KB_STRUCTURED",
}
REVIEWED_STATUSES = {"human_reviewed", "approved"}


def log(msg: str) -> None:
    print(f"[ragas] {msg}", flush=True)


def die(msg: str, code: int = 1) -> None:
    print(f"[ragas][ERROR] {msg}", file=sys.stderr, flush=True)
    sys.exit(code)


def load_questions(path: pathlib.Path, sources: set[str] | None,
                   query_mode: str, reference_mode: str = "auto",
                   allow_draft_reference: bool = False) -> list[dict[str, Any]]:
    with open(path, "r", encoding="utf-8") as f:
        root = yaml.safe_load(f)
    out: list[dict[str, Any]] = []
    for item in root.get("questions", []):
        source = str(item.get("source", "")).strip()
        if sources and source not in sources:
            continue
        question = item.get("question")
        if query_mode == "hard" and item.get("query_hard"):
            question = item.get("query_hard")
        if not question:
            continue
        reference_answer = str(item.get("reference_answer") or "").strip()
        answerable = bool(item.get("answerable", True))
        review_status = str(item.get("review_status") or "").strip()
        reviewed_reference = review_status in REVIEWED_STATUSES
        if reference_mode in {"auto", "strict"} and answerable:
            if reference_mode == "strict" and not reference_answer:
                die(f"{item.get('id')}: strict 模式要求 reference_answer（人工审核标准答案）")
            if reference_answer and not reviewed_reference and not allow_draft_reference:
                die(f"{item.get('id')}: reference_answer 的 review_status={review_status or '<missing>'}，"
                    "未完成人工审核；如仅验证链路，请显式加 --allow-draft-reference")
            if reference_mode == "strict" and not allow_draft_reference:
                missing_gold = [field for field in ("gold_evidence", "split", "origin", "question_type")
                                if not item.get(field)]
                if missing_gold:
                    die(f"{item.get('id')}: strict 正式集缺少 {', '.join(missing_gold)}；"
                        "请先完成 DATASET_REVIEW.md 中的证据和元数据审核")
        if reference_mode == "strict":
            ground_truth = reference_answer
            ground_truth_kind = "reference_answer" if reviewed_reference else "reference_answer_draft"
        elif reference_mode == "proxy":
            ground_truth = build_ground_truth(item.get("key_points", []))
            ground_truth_kind = "keypoint_proxy"
        elif reference_answer:
            ground_truth = reference_answer
            ground_truth_kind = "reference_answer" if reviewed_reference else "reference_answer_draft"
        else:
            ground_truth = build_ground_truth(item.get("key_points", []))
            ground_truth_kind = "keypoint_proxy"
        out.append({
            "id": item.get("id"),
            "source": source,
            "difficulty": item.get("difficulty"),
            "question": question,
            "reference_answer": reference_answer,
            "review_status": review_status,
            "split": item.get("split"),
            "origin": item.get("origin"),
            "question_type": item.get("question_type"),
            "gold_evidence": item.get("gold_evidence") or [],
            "ground_truth": ground_truth,
            "ground_truth_kind": ground_truth_kind,
            "answerable": answerable,
            "expected_refusal": bool(item.get("expected_refusal", not answerable)),
            "key_points": item.get("key_points", []),
        })
    if reference_mode == "auto":
        answerable_items = [item for item in out if item.get("answerable", True)]
        if not answerable_items or not all(item.get("reference_answer") for item in answerable_items):
            # 混合数据集统一降级，避免同一份报告一部分按标准答案、一部分按代理解释。
            for item in out:
                item["ground_truth"] = build_ground_truth(item.get("key_points", []))
                item["ground_truth_kind"] = "keypoint_proxy"
    return out


def build_ground_truth(key_points: list[Any]) -> str:
    """把关键点同义词组压成一句参考要点：每组取首个词，用「、」连接。"""
    terms: list[str] = []
    for group in key_points:
        if isinstance(group, list) and group:
            terms.append(str(group[0]))
        elif group:
            terms.append(str(group))
    if not terms:
        return ""
    return "参考要点应覆盖：" + "、".join(terms) + "。"


def resolve_kb_map() -> dict[str, int]:
    mapping: dict[str, int] = {}
    for source, env in SOURCE_ENV.items():
        raw = os.getenv(env, "").strip()
        if raw and raw.isdigit() and int(raw) > 0:
            mapping[source] = int(raw)
    return mapping


def stratified_limit(items: list[dict[str, Any]], limit: int) -> list[dict[str, Any]]:
    """按 source/difficulty 轮询抽样，避免 ``--limit 20`` 只跑数据集开头的 Redis。"""
    if limit <= 0 or limit >= len(items):
        return items
    buckets: dict[tuple[str, str], deque[dict[str, Any]]] = defaultdict(deque)
    for item in items:
        buckets[(str(item.get("source") or ""), str(item.get("difficulty") or ""))].append(item)
    selected: list[dict[str, Any]] = []
    keys = sorted(buckets)
    while len(selected) < limit and keys:
        next_keys: list[tuple[str, str]] = []
        for key in keys:
            bucket = buckets[key]
            if bucket and len(selected) < limit:
                selected.append(bucket.popleft())
            if bucket:
                next_keys.append(key)
        keys = next_keys
    return selected


def export_qa(api_base: str, token: str, kb_ids: list[int],
              items: list[dict[str, str]]) -> list[dict[str, Any]]:
    url = f"{api_base.rstrip('/')}/api/v1/knowledge-bases/eval/export-qa"
    headers = {"Content-Type": "application/json"}
    if token:
        headers["satoken"] = token
    payload = {"knowledgeBaseIds": kb_ids,
               "items": [{
                   "id": it.get("id"),
                   "source": it.get("source"),
                   "difficulty": it.get("difficulty"),
                   "question": it["question"],
                   "groundTruth": it.get("ground_truth"),
                   "referenceAnswer": it.get("reference_answer"),
               } for it in items]}
    resp = requests.post(url, headers=headers, json=payload, timeout=600)
    resp.raise_for_status()
    body = resp.json()
    data = body.get("data") if isinstance(body, dict) else None
    if not data or "records" not in data:
        die(f"导出接口返回异常: {body}")
    return data["records"]


def run_export(args: argparse.Namespace) -> list[dict[str, Any]]:
    dataset_path = pathlib.Path(args.dataset)
    if not dataset_path.exists():
        die(f"评测集不存在: {dataset_path}")
    sources = {s.strip() for s in args.sources.split(",") if s.strip()} if args.sources else None
    questions = load_questions(dataset_path, sources, args.query_mode, args.reference_mode,
                               getattr(args, "allow_draft_reference", False))
    questions = stratified_limit(questions, args.limit)
    if not questions:
        die("筛选后没有可评测的问题")

    kb_map = resolve_kb_map()
    if not kb_map:
        die("未配置任何知识库 id（RAGEVAL_KB_REDIS/MYSQL/DISTRIBUTED/JVM/SPRING）")

    token = os.getenv("RAGEVAL_SATOKEN", "").strip()
    if not token:
        die("缺少 RAGEVAL_SATOKEN（后端接口需要 Sa-Token 会话）")

    grouped: dict[int, list[dict[str, str]]] = defaultdict(list)
    skipped = 0
    for q in questions:
        kb_id = kb_map.get(q["source"])
        if not kb_id:
            skipped += 1
            continue
        grouped[kb_id].append(q)
    log(f"评测题 {sum(len(v) for v in grouped.values())}（跳过未配置 source 的题 {skipped}），"
        f"知识库分组 {list(grouped.keys())}")

    records: list[dict[str, Any]] = []
    for kb_id, items in grouped.items():
        log(f"导出 kb={kb_id} 的 {len(items)} 题（走完整 RAG 生成，耗时取决于题量与模型）...")
        got = export_qa(args.api_base, token, [kb_id], items)
        records.extend(got)
    # 兼容旧后端：如果响应没有回传元数据，按 question 回填；新后端优先使用 id。
    by_id = {str(q.get("id")): q for q in questions if q.get("id")}
    by_question = {q.get("question"): q for q in questions}
    enriched: list[dict[str, Any]] = []
    for record in records:
        candidate = by_id.get(str(record.get("id"))) or by_question.get(record.get("question"))
        normalized = normalize_record(record)
        if candidate:
            for key in ("id", "source", "difficulty", "reference_answer", "ground_truth_kind",
                        "answerable", "expected_refusal", "key_points", "review_status", "split",
                        "origin", "question_type", "gold_evidence"):
                # 这些标签的权威来源是评测集；旧后端没有回传 answerable 时，不能让
                # normalize_record 的默认 true 覆盖掉题目上的 unanswerable 标记。
                if key in {"answerable", "expected_refusal", "key_points", "ground_truth_kind",
                           "gold_evidence"} \
                        or normalized.get(key) in (None, "", []):
                    normalized[key] = candidate.get(key)
            if not normalized.get("ground_truth"):
                normalized["ground_truth"] = candidate.get("ground_truth")
        enriched.append(normalized)
    return enriched


def normalize_record(record: dict[str, Any]) -> dict[str, Any]:
    """统一新旧后端字段名，保证历史 JSONL 仍可复评。"""
    normalized = {
        "id": record.get("id"),
        "source": record.get("source"),
        "difficulty": record.get("difficulty"),
        "question": record.get("question"),
        "answer": record.get("answer") or "",
        "contexts": record.get("contexts") or [],
        "reference_answer": record.get("reference_answer")
            or record.get("referenceAnswer") or "",
        "review_status": record.get("review_status") or record.get("reviewStatus") or "",
        "split": record.get("split"),
        "origin": record.get("origin"),
        "question_type": record.get("question_type") or record.get("questionType"),
        "gold_evidence": record.get("gold_evidence") or record.get("goldEvidence") or [],
        "ground_truth": record.get("ground_truth")
            or record.get("groundTruth") or "",
        "ground_truth_kind": record.get("ground_truth_kind") or "unknown",
        "latency_ms": record.get("latency_ms", record.get("latencyMs")),
        "no_evidence": bool(record.get("no_evidence", record.get("noEvidence", False))),
        "route_source": record.get("route_source") or record.get("routeSource") or "",
        "route_intent": record.get("route_intent") or record.get("routeIntent") or "",
        "route_confidence": record.get("route_confidence", record.get("routeConfidence")),
        "route_reasoning": record.get("route_reasoning") or record.get("routeReasoning") or "",
        "answerable": bool(record.get("answerable", True)),
        "expected_refusal": bool(record.get("expected_refusal", False)),
        "key_points": record.get("key_points") or [],
    }
    # RAGAS 的逐题结果使用 metric_* 前缀写回记录。保留这些诊断字段，
    # 否则均值虽然能打印，JSONL 和低分样本表却无法追溯到具体题目。
    normalized.update({key: value for key, value in record.items()
                       if str(key).startswith("metric_")})
    return normalized


def write_jsonl(records: list[dict[str, Any]], out_dir: pathlib.Path, ts: str,
                prefix: str = "qa-export") -> pathlib.Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"{prefix}-{ts}.jsonl"
    with open(path, "w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(normalize_record(r), ensure_ascii=False) + "\n")
    log(f"JSONL 已写入: {path}（{len(records)} 条）")
    return path


def load_jsonl(path: pathlib.Path) -> list[dict[str, Any]]:
    records = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(normalize_record(json.loads(line)))
    return records


def build_evaluator():
    """构造 RAGAS 评测模型（DashScope OpenAI 兼容端点）。"""
    api_key = os.getenv("DASHSCOPE_API_KEY", "").strip()
    if not api_key:
        die("缺少 DASHSCOPE_API_KEY（RAGAS 评测需要 judge 模型）")
    base_url = os.getenv("RAGAS_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
    llm_model = os.getenv("RAGAS_LLM_MODEL", "qwen-plus")
    embed_model = os.getenv("RAGAS_EMBED_MODEL", "text-embedding-v3")

    from langchain_openai import ChatOpenAI, OpenAIEmbeddings
    from ragas.embeddings import LangchainEmbeddingsWrapper
    from ragas.llms import LangchainLLMWrapper

    seed = int(os.getenv("RAGAS_SEED", "42"))
    llm = LangchainLLMWrapper(ChatOpenAI(model=llm_model, api_key=api_key,
                                         base_url=base_url, temperature=0, seed=seed))
    embeddings = LangchainEmbeddingsWrapper(OpenAIEmbeddings(
        model=embed_model, api_key=api_key, base_url=base_url, check_embedding_ctx_length=False))
    max_workers = int(os.getenv("RAGAS_MAX_WORKERS", "8"))
    from ragas.run_config import RunConfig
    run_config = RunConfig(max_workers=max_workers, seed=seed)
    log(f"评测模型: llm={llm_model}, embed={embed_model}, seed={seed}, workers={max_workers} @ {base_url}")
    return llm, embeddings, run_config


def run_ragas(records: list[dict[str, Any]], ground_truth_mode: str,
              include_answer_correctness: bool = False) -> tuple[dict[str, float], list[dict[str, Any]]]:
    from ragas import EvaluationDataset, evaluate
    from ragas.dataset_schema import SingleTurnSample
    from ragas.metrics import (
        AnswerCorrectness,
        AnswerRelevancy,
        Faithfulness,
        LLMContextPrecisionWithReference,
        LLMContextRecall,
    )

    # 无答案题不应参与 context_recall / answer_correctness；它们单独进入拒答诊断。
    evaluable = [(index, r) for index, r in enumerate(records) if r.get("answerable", True)]
    if not evaluable:
        return {}, [normalize_record(r) for r in records]
    llm, embeddings, run_config = build_evaluator()
    samples = []
    for _, r in evaluable:
        contexts = r.get("contexts") or []
        if not contexts:
            contexts = ["（无召回内容）"]
        samples.append(SingleTurnSample(
            user_input=r.get("question") or "",
            response=r.get("answer") or "",
            retrieved_contexts=[str(c) for c in contexts],
            reference=r.get("ground_truth") or "",
        ))
    dataset = EvaluationDataset(samples=samples)
    metrics = [
        Faithfulness(),
        AnswerRelevancy(),
        LLMContextPrecisionWithReference(),
        LLMContextRecall(),
    ]
    if include_answer_correctness and ground_truth_mode == "reference_answer":
        metrics.append(AnswerCorrectness())
    log(f"开始 RAGAS 评测：{len(samples)} 条 × {len(metrics)} 指标 ...")
    result = evaluate(dataset=dataset, metrics=metrics, llm=llm, embeddings=embeddings,
                      run_config=run_config, raise_exceptions=False)
    df = result.to_pandas()
    scores: dict[str, float] = {}
    metric_columns = [col for col in df.columns
                      if col not in ("user_input", "response", "retrieved_contexts", "reference")]
    for col in metric_columns:
        values = []
        for value in df[col].tolist():
            try:
                numeric = float(value)
                if not math.isnan(numeric):
                    values.append(numeric)
            except (TypeError, ValueError):
                pass
        if values:
            scores[col] = sum(values) / len(values)

    per_case: list[dict[str, Any]] = []
    per_case_by_index: dict[int, dict[str, Any]] = {}
    for row_index, (original_index, record) in enumerate(evaluable):
        row = normalize_record(record)
        for col in metric_columns:
            value = df.iloc[row_index][col]
            try:
                numeric = float(value)
                row[f"metric_{col}"] = None if math.isnan(numeric) else numeric
            except (TypeError, ValueError):
                row[f"metric_{col}"] = None
        per_case_by_index[original_index] = row
    per_case = [per_case_by_index.get(index, normalize_record(record))
                for index, record in enumerate(records)]
    return scores, per_case


def is_refusal(answer: str) -> bool:
    # 评测拒答是独立于 RAGAS 四指标的诊断：允许“无法直接给出/确认”等
    # 分层拒答措辞，不能只依赖某一句固定模板，否则会把正确的部分拒答误报成幻觉。
    return any(token in answer for token in (
        "未检索到相关信息", "没有找到相关信息", "没有找到关于", "信息不足", "信息缺失",
        "无法根据提供内容回答", "无法直接确认", "无法直接给出", "无法提供",
        "超出知识库范围", "不知道", "无法回答"))


def refusal_metrics(records: list[dict[str, Any]]) -> dict[str, float]:
    unanswerable = [r for r in records if not r.get("answerable", True)]
    if not unanswerable:
        return {}
    refused = [r for r in unanswerable
               if r.get("no_evidence") or is_refusal(r.get("answer") or "")]
    recall = len(refused) / len(unanswerable)
    return {
        "refusal_recall": recall,
        "unanswerable_hallucination_rate": 1.0 - recall,
    }


def choose_ground_truth_mode(records: list[dict[str, Any]], requested: str,
                             allow_draft_reference: bool) -> str:
    """根据记录元数据决定本次报告能否称为正式 reference 评测。"""
    if requested == "proxy":
        return "keypoint_proxy"
    answerable = [r for r in records if r.get("answerable", True)]
    if not answerable:
        return "keypoint_proxy"
    has_reference = all(str(r.get("reference_answer") or "").strip() for r in answerable)
    if requested == "strict" and not has_reference:
        die("strict 模式要求所有可回答样本都有 reference_answer；请先补齐标准答案")
    if not has_reference:
        return "keypoint_proxy"
    reviewed = all(str(r.get("review_status") or "").strip() in REVIEWED_STATUSES
                   for r in answerable)
    if reviewed:
        return "reference_answer"
    if allow_draft_reference:
        return "reference_answer_draft"
    die("reference_answer 尚未全部人工审核（review_status 不是 human_reviewed/approved）；"
        "如仅验证 smoke 链路，请显式加 --allow-draft-reference")
    return "reference_answer_draft"  # 仅帮助静态类型检查，die 后不会到达


def write_case_jsonl(records: list[dict[str, Any]], out_dir: pathlib.Path, ts: str) -> pathlib.Path:
    return write_jsonl(records, out_dir, ts, prefix="ragas-cases")


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(p * len(ordered)) - 1))
    return ordered[index]


def write_report(scores: dict[str, float], cases: list[dict[str, Any]], jsonl: pathlib.Path,
                 out_dir: pathlib.Path, ts: str, ground_truth_mode: str) -> pathlib.Path:
    quality_scores = {k: v for k, v in scores.items()
                      if k not in {"refusal_recall", "unanswerable_hallucination_rate"}}
    path = out_dir / f"ragas-report-{ts}.md"
    lines = [
        "# RAGAS 生成质量评测报告",
        "",
        f"- 生成时间：{datetime.datetime.now():%Y-%m-%d %H:%M:%S}",
        f"- 样本数：{len(cases)}",
        f"- 源数据：`{jsonl.name}`",
        f"- ground truth：`{ground_truth_mode}`",
        f"- 评测模型：{os.getenv('RAGAS_LLM_MODEL', 'qwen-plus')} "
        f"/ {os.getenv('RAGAS_EMBED_MODEL', 'text-embedding-v3')}",
        "",
        "| 指标 | 得分 | 含义 |",
        "|------|------|------|",
    ]
    meaning = {
        "faithfulness": "回答是否忠于检索上下文（越高越少幻觉）",
        "answer_relevancy": "回答与问题的相关性",
        "answer_correctness": "回答与人工参考答案的正确性（仅严格模式）",
        "llm_context_precision_with_reference": "检索上下文的精确率（相关内容排在前面）",
        "context_precision": "检索上下文的精确率",
        "context_recall": "检索上下文对参考答案的覆盖率",
    }
    for k, v in quality_scores.items():
        lines.append(f"| {k} | {v:.4f} | {meaning.get(k, '')} |")
    lines += ["", "## 分层结果", "",
              "| 分组 | 样本数 | " + " | ".join(quality_scores.keys()) + " |",
              "|------|------|" + "------|" * len(quality_scores)]
    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for case in cases:
        groups[f"source={case.get('source') or 'unknown'}"].append(case)
        groups[f"difficulty={case.get('difficulty') or 'unknown'}"].append(case)
    for name, group in sorted(groups.items()):
        values = []
        for metric in quality_scores:
            nums = [case.get(f"metric_{metric}") for case in group
                    if isinstance(case.get(f"metric_{metric}"), (int, float))]
            values.append(f"{sum(nums) / len(nums):.4f}" if nums else "-")
        lines.append(f"| {name} | {len(group)} | " + " | ".join(values) + " |")
    refusal = refusal_metrics(cases)
    if refusal:
        lines += ["", "## 拒答诊断", "", "| 指标 | 得分 |", "|------|------|"]
        lines.extend(f"| {k} | {v:.4f} |" for k, v in refusal.items())
    latencies = [float(case["latency_ms"]) for case in cases
                 if isinstance(case.get("latency_ms"), (int, float)) and case.get("latency_ms") is not None]
    if latencies:
        lines += ["", "## 工程指标", "", "| 指标 | 毫秒 |", "|------|------|"]
        lines += [f"| p50 latency | {percentile(latencies, 0.50):.0f} |",
                  f"| p95 latency | {percentile(latencies, 0.95):.0f} |",
                  f"| max latency | {max(latencies):.0f} |"]
    route_counts = defaultdict(int)
    for case in cases:
        route_counts[str(case.get("route_source") or "unknown")] += 1
    if route_counts:
        lines += ["", "## 数据源路由诊断", "", "| route_source | 样本数 |", "|------|------|"]
        lines.extend(f"| {route} | {count} |" for route, count in sorted(route_counts.items()))
    bad_cases = []
    for case in cases:
        metric_values = [v for k, v in case.items()
                         if k.startswith("metric_") and isinstance(v, (int, float))]
        if metric_values and sum(metric_values) / len(metric_values) < 0.4:
            bad_cases.append(case)
    if bad_cases:
        lines += ["", "## 低分样本（平均 RAGAS < 0.4）", "",
                  "| id | source | question |", "|------|------|------|"]
        lines.extend(f"| {c.get('id') or '-'} | {c.get('source') or '-'} | "
                     f"{str(c.get('question') or '').replace('|', '/')} |" for c in bad_cases[:20])
    lines += [
        "",
        "> keypoint_proxy 只适合开发期检索诊断；只有 reference_answer 经过人工审核时，",
        "> 才把 context_recall / answer_correctness 用作正式生成质量结论。",
        "> reference_answer_draft 仅用于 smoke 链路验证，不能写入简历或作为模型优劣结论。",
        "> 写入简历前请保留本报告与对应 QA 导出 JSONL，保证数字可追溯到一次完整运行。",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8")
    log(f"报告已写入: {path}")
    return path


def main() -> None:
    parser = argparse.ArgumentParser(description="RAGAS 生成质量评测 runner")
    parser.add_argument("--dataset", default=str(DEFAULT_DATASET), help="评测集 YAML 路径")
    parser.add_argument("--api-base", default=os.getenv("RAGEVAL_API_BASE", "http://localhost:8082"))
    parser.add_argument("--out-dir", default=str(DEFAULT_OUT))
    parser.add_argument("--limit", type=int, default=0, help="分层抽样 N 题（0=全部）")
    parser.add_argument("--sources", default="", help="逗号分隔的 source 过滤，如 redis,mysql")
    parser.add_argument("--query-mode", choices=["easy", "hard"], default="easy",
                        help="用标准问题还是口语化 query_hard")
    parser.add_argument("--reference-mode", choices=["auto", "proxy", "strict"], default="auto",
                        help="标准答案模式：strict 要求已审核 reference；proxy 使用 key_points 代理；auto 自动选择")
    parser.add_argument("--allow-draft-reference", action="store_true",
                        help="允许使用 review_status=draft 的答案，仅用于 smoke；报告会标记 reference_answer_draft")
    parser.add_argument("--with-answer-correctness", action="store_true",
                        help="额外计算 AnswerCorrectness；默认只跑四个核心 RAGAS 指标")
    parser.add_argument("--export-only", action="store_true", help="只导出 JSONL，不跑 RAGAS")
    parser.add_argument("--from-jsonl", default="", help="跳过导出，直接用已有 JSONL 跑 RAGAS")
    args = parser.parse_args()

    out_dir = pathlib.Path(args.out_dir)
    ts = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")

    if args.from_jsonl:
        jsonl_path = pathlib.Path(args.from_jsonl)
        if not jsonl_path.exists():
            die(f"JSONL 不存在: {jsonl_path}")
        records = load_jsonl(jsonl_path)
        log(f"从 {jsonl_path} 载入 {len(records)} 条")
    else:
        raw = run_export(args)
        records = [normalize_record(r) for r in raw]
        jsonl_path = write_jsonl(raw, out_dir, ts)

    if args.export_only:
        log("--export-only 指定，跳过 RAGAS 评测")
        return

    ground_truth_mode = choose_ground_truth_mode(records, args.reference_mode,
                                                 args.allow_draft_reference)
    if ground_truth_mode == "keypoint_proxy":
        # --from-jsonl 也必须遵守显式 proxy，不沿用旧导出中的 reference ground truth。
        for record in records:
            record["ground_truth"] = build_ground_truth(record.get("key_points", []))
            record["ground_truth_kind"] = "keypoint_proxy"
    scores, cases = run_ragas(records, ground_truth_mode, args.with_answer_correctness)
    scores.update(refusal_metrics(cases))
    log("评测结果：")
    for k, v in scores.items():
        log(f"  {k}: {v:.4f}")
    case_path = write_case_jsonl(cases, out_dir, ts)
    log(f"逐题结果已写入: {case_path}")
    write_report(scores, cases, jsonl_path, out_dir, ts, ground_truth_mode)


if __name__ == "__main__":
    main()
