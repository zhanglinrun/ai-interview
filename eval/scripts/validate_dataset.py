#!/usr/bin/env python3
"""评测集结构校验（P4.4 CI harness 门禁）。

不连任何外部服务，纯静态校验 eval-dataset.yaml：
  - 每题含 id / source / difficulty / question / key_points；
  - key_points 为「同义词组」列表（list[list[str]]）且非空；
  - id 全局唯一；
  - difficulty ∈ {fact, concept, synthesis}；
  - 题量达标（默认 >= 80，可用 --min-questions 调整）。

任一不满足即非零退出，阻断 PR（防止评测集腐化 / N14 式文档-代码漂移）。

用法：python validate_dataset.py [--dataset PATH] [--min-questions N]
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
