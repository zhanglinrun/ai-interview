#!/usr/bin/env python3
"""RAGAS 生成质量评测 runner（P4.3 评测闭环）。

流程：
  1. 读评测集 ``eval/rag-retrieval/eval-dataset.yaml``；
  2. 每题 ground_truth 由 ``key_points`` 关键点组装（每组取首个同义词，作为参考要点代理）；
  3. 按 ``source`` 映射到知识库 id（环境变量 ``RAGEVAL_KB_*``），跳过未配置的 source；
  4. 调后端 ``POST /api/v1/knowledge-bases/eval/export-qa`` 批量走完整 RAG 生成，拿回
     ``{question, answer, contexts[], ground_truth}``，落 JSONL；
  5. 用 RAGAS 计算 faithfulness / answer_relevancy / context_precision / context_recall；
  6. 输出带时间戳的 markdown 报告到 ``eval/ragas/.work/``。

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
from collections import defaultdict
from typing import Any

import requests
import yaml

HERE = pathlib.Path(__file__).resolve().parent
DEFAULT_DATASET = HERE.parent / "rag-retrieval" / "eval-dataset.yaml"
DEFAULT_OUT = HERE / ".work"
SOURCE_ENV = {
    "redis": "RAGEVAL_KB_REDIS",
    "mysql": "RAGEVAL_KB_MYSQL",
    "distributed": "RAGEVAL_KB_DISTRIBUTED",
    "jvm": "RAGEVAL_KB_JVM",
    "spring": "RAGEVAL_KB_SPRING",
}


def log(msg: str) -> None:
    print(f"[ragas] {msg}", flush=True)


def die(msg: str, code: int = 1) -> None:
    print(f"[ragas][ERROR] {msg}", file=sys.stderr, flush=True)
    sys.exit(code)


def load_questions(path: pathlib.Path, sources: set[str] | None,
                   query_mode: str) -> list[dict[str, Any]]:
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
        out.append({
            "id": item.get("id"),
            "source": source,
            "question": question,
            "ground_truth": build_ground_truth(item.get("key_points", [])),
        })
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


def export_qa(api_base: str, token: str, kb_ids: list[int],
              items: list[dict[str, str]]) -> list[dict[str, Any]]:
    url = f"{api_base.rstrip('/')}/api/v1/knowledge-bases/eval/export-qa"
    headers = {"Content-Type": "application/json"}
    if token:
        headers["satoken"] = token
    payload = {"knowledgeBaseIds": kb_ids,
               "items": [{"question": it["question"], "groundTruth": it["ground_truth"]}
                         for it in items]}
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
    questions = load_questions(dataset_path, sources, args.query_mode)
    if args.limit > 0:
        questions = questions[: args.limit]
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
    return records


def write_jsonl(records: list[dict[str, Any]], out_dir: pathlib.Path, ts: str) -> pathlib.Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"qa-export-{ts}.jsonl"
    with open(path, "w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps({
                "question": r.get("question"),
                "answer": r.get("answer"),
                "contexts": r.get("contexts", []),
                "ground_truth": r.get("groundTruth") or r.get("ground_truth"),
            }, ensure_ascii=False) + "\n")
    log(f"JSONL 已写入: {path}（{len(records)} 条）")
    return path


def load_jsonl(path: pathlib.Path) -> list[dict[str, Any]]:
    records = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(json.loads(line))
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

    llm = LangchainLLMWrapper(ChatOpenAI(model=llm_model, api_key=api_key,
                                         base_url=base_url, temperature=0))
    embeddings = LangchainEmbeddingsWrapper(OpenAIEmbeddings(
        model=embed_model, api_key=api_key, base_url=base_url, check_embedding_ctx_length=False))
    log(f"评测模型: llm={llm_model}, embed={embed_model} @ {base_url}")
    return llm, embeddings


def run_ragas(records: list[dict[str, Any]]) -> dict[str, float]:
    from ragas import EvaluationDataset, evaluate
    from ragas.dataset_schema import SingleTurnSample
    from ragas.metrics import (
        Faithfulness,
        LLMContextPrecisionWithReference,
        LLMContextRecall,
        ResponseRelevancy,
    )

    llm, embeddings = build_evaluator()
    samples = []
    for r in records:
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
        ResponseRelevancy(),
        LLMContextPrecisionWithReference(),
        LLMContextRecall(),
    ]
    log(f"开始 RAGAS 评测：{len(samples)} 条 × {len(metrics)} 指标 ...")
    result = evaluate(dataset=dataset, metrics=metrics, llm=llm, embeddings=embeddings)
    df = result.to_pandas()
    scores: dict[str, float] = {}
    for col in df.columns:
        if col in ("user_input", "response", "retrieved_contexts", "reference"):
            continue
        series = df[col]
        try:
            scores[col] = float(series.mean())
        except (TypeError, ValueError):
            continue
    return scores


def write_report(scores: dict[str, float], count: int, jsonl: pathlib.Path,
                 out_dir: pathlib.Path, ts: str) -> pathlib.Path:
    path = out_dir / f"ragas-report-{ts}.md"
    lines = [
        "# RAGAS 生成质量评测报告",
        "",
        f"- 生成时间：{datetime.datetime.now():%Y-%m-%d %H:%M:%S}",
        f"- 样本数：{count}",
        f"- 源数据：`{jsonl.name}`",
        f"- 评测模型：{os.getenv('RAGAS_LLM_MODEL', 'qwen-plus')} "
        f"/ {os.getenv('RAGAS_EMBED_MODEL', 'text-embedding-v3')}",
        "",
        "| 指标 | 得分 | 含义 |",
        "|------|------|------|",
    ]
    meaning = {
        "faithfulness": "回答是否忠于检索上下文（越高越少幻觉）",
        "answer_relevancy": "回答与问题的相关性",
        "response_relevancy": "回答与问题的相关性",
        "llm_context_precision_with_reference": "检索上下文的精确率（相关内容排在前面）",
        "context_precision": "检索上下文的精确率",
        "context_recall": "检索上下文对参考答案的覆盖率",
    }
    for k, v in scores.items():
        lines.append(f"| {k} | {v:.4f} | {meaning.get(k, '')} |")
    lines += [
        "",
        "> ground_truth 由评测集 key_points 关键点组装（参考要点代理），",
        "> 故 context_recall / 相关指标反映「是否覆盖预期要点」，非严格标准答案比对。",
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
    parser.add_argument("--limit", type=int, default=0, help="只跑前 N 题（0=全部）")
    parser.add_argument("--sources", default="", help="逗号分隔的 source 过滤，如 redis,mysql")
    parser.add_argument("--query-mode", choices=["easy", "hard"], default="easy",
                        help="用标准问题还是口语化 query_hard")
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
        records = [{
            "question": r.get("question"),
            "answer": r.get("answer"),
            "contexts": r.get("contexts", []),
            "ground_truth": r.get("groundTruth") or r.get("ground_truth"),
        } for r in raw]
        jsonl_path = write_jsonl(raw, out_dir, ts)

    if args.export_only:
        log("--export-only 指定，跳过 RAGAS 评测")
        return

    scores = run_ragas(records)
    log("评测结果：")
    for k, v in scores.items():
        log(f"  {k}: {v:.4f}")
    write_report(scores, len(records), jsonl_path, out_dir, ts)


if __name__ == "__main__":
    main()
