#!/usr/bin/env python3
"""评测集结构校验（P4.4 CI harness 门禁）。

不连任何外部服务，纯静态校验 eval-dataset.yaml：
  - 每题含 id / source / difficulty / question / key_points；
  - key_points 为「同义词组」列表（list[list[str]]）且非空；
  - id 全局唯一；
  - difficulty ∈ {fact, concept, synthesis}；
  - 可选的 reference_answer / answerable 字段类型正确；
  - 可选的 split / origin / question_type / review_status / gold_evidence 类型正确；
  - 题量达标（默认 >= 80，可用 --min-questions 调整）。

任一不满足即非零退出，阻断 PR（防止评测集腐化 / N14 式文档-代码漂移）。

用法：python validate_dataset.py [--dataset PATH] [--min-questions N]
       [--require-reference] [--require-reviewed-reference]
"""
from __future__ import annotations

import argparse
import pathlib
import sys

import yaml

VALID_DIFFICULTY = {"fact", "concept", "synthesis"}
REQUIRED_FIELDS = ("id", "source", "difficulty", "question", "key_points")


def main() -> int:
    here = pathlib.Path(__file__).resolve().parent
    default_dataset = here.parent / "rag-retrieval" / "eval-dataset.yaml"
    parser = argparse.ArgumentParser(description="评测集结构校验")
    parser.add_argument("--dataset", default=str(default_dataset))
    parser.add_argument("--min-questions", type=int, default=80)
    parser.add_argument("--require-reference", action="store_true",
                        help="要求每个可回答题都有非空 reference_answer")
    parser.add_argument("--require-reviewed-reference", action="store_true",
                        help="正式生成集门禁：要求 reference_answer 已人工审核，并有 split/origin/question_type/gold_evidence")
    args = parser.parse_args()

    path = pathlib.Path(args.dataset)
    if not path.exists():
        print(f"[validate] 评测集不存在: {path}", file=sys.stderr)
        return 1

    with open(path, "r", encoding="utf-8") as f:
        root = yaml.safe_load(f)

    questions = root.get("questions") if isinstance(root, dict) else None
    if not questions:
        print("[validate] 缺少 questions 列表", file=sys.stderr)
        return 1

    errors: list[str] = []
    seen_ids: set[str] = set()
    by_source: dict[str, int] = {}
    for idx, q in enumerate(questions):
        tag = q.get("id", f"#{idx}")
        for field in REQUIRED_FIELDS:
            if field not in q or q[field] in (None, "", []):
                errors.append(f"{tag}: 缺少字段 {field}")
        qid = q.get("id")
        if qid in seen_ids:
            errors.append(f"{tag}: id 重复")
        seen_ids.add(qid)
        diff = q.get("difficulty")
        if diff and diff not in VALID_DIFFICULTY:
            errors.append(f"{tag}: difficulty 非法 '{diff}'（应为 {VALID_DIFFICULTY}）")
        kp = q.get("key_points")
        if isinstance(kp, list):
            for gi, group in enumerate(kp):
                if not isinstance(group, list) or not group:
                    errors.append(f"{tag}: key_points[{gi}] 应为非空同义词组列表")
        if "reference_answer" in q and q["reference_answer"] not in (None, "") \
                and not isinstance(q["reference_answer"], str):
            errors.append(f"{tag}: reference_answer 应为字符串")
        if args.require_reference and q.get("answerable", True) \
                and not str(q.get("reference_answer") or "").strip():
            errors.append(f"{tag}: 缺少人工审核 reference_answer")
        if "answerable" in q and not isinstance(q["answerable"], bool):
            errors.append(f"{tag}: answerable 应为 true/false")
        for field in ("split", "origin", "question_type", "review_status"):
            if field in q and q[field] not in (None, "") and not isinstance(q[field], str):
                errors.append(f"{tag}: {field} 应为字符串")
        if "gold_evidence" in q and q["gold_evidence"] not in (None, "") \
                and not isinstance(q["gold_evidence"], list):
            errors.append(f"{tag}: gold_evidence 应为列表")
        if "expected_refusal" in q and not isinstance(q["expected_refusal"], bool):
            errors.append(f"{tag}: expected_refusal 应为 true/false")
        if args.require_reviewed_reference and q.get("answerable", True):
            if not str(q.get("reference_answer") or "").strip():
                errors.append(f"{tag}: 缺少 reference_answer")
            if q.get("review_status") not in {"human_reviewed", "approved"}:
                errors.append(f"{tag}: review_status 必须是 human_reviewed/approved")
            for field in ("split", "origin", "question_type"):
                if not str(q.get(field) or "").strip():
                    errors.append(f"{tag}: 缺少正式生成集字段 {field}")
            if not isinstance(q.get("gold_evidence"), list) or not q.get("gold_evidence"):
                errors.append(f"{tag}: 缺少非空 gold_evidence")
            else:
                for ei, evidence in enumerate(q["gold_evidence"]):
                    if not isinstance(evidence, dict):
                        errors.append(f"{tag}: gold_evidence[{ei}] 应为对象（至少包含 doc_id/quote）")
                    elif not str(evidence.get("doc_id") or "").strip() \
                            or not str(evidence.get("quote") or "").strip():
                        errors.append(f"{tag}: gold_evidence[{ei}] 缺少 doc_id 或 quote")
        src = q.get("source")
        if src:
            by_source[src] = by_source.get(src, 0) + 1

    total = len(questions)
    if total < args.min_questions:
        errors.append(f"题量 {total} < 要求 {args.min_questions}")

    if errors:
        print(f"[validate] 校验失败（{len(errors)} 项）:", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 1

    print(f"[validate] OK：{total} 题，唯一 id {len(seen_ids)} 个")
    print(f"[validate] 按 source 分布: "
          + ", ".join(f"{k}={v}" for k, v in sorted(by_source.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
