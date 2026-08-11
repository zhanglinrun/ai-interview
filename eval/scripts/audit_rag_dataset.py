#!/usr/bin/env python3
"""RAG 评测集质量审计。

这个脚本不判断答案的领域事实是否正确（那必须由人工/领域专家完成），
但会把最容易让评测失真的结构问题显式报告出来：

* 重复题、缺字段、题型/来源/难度分布；
* reference answer、gold evidence、review status 是否完整；
* 无答案题、holdout 是否存在；
* ``key_points`` 的关键词泄漏、过长同义词组和通用词误命中风险。

用法：
    python audit_rag_dataset.py --dataset ../ragas/generation-dataset.yaml
    python audit_rag_dataset.py --dataset ../ragas/generation-dataset.yaml \
        --require-reviewed-gold

``--require-reviewed-gold`` 用于正式生成集门禁。当前仓库的 20 题仍是草案，
因此有意会在这个模式下失败，提醒维护者先补人工审核和证据标注。
"""
from __future__ import annotations

import argparse
import collections
import pathlib
import sys
from typing import Any

import yaml


REQUIRED_FIELDS = ("id", "source", "difficulty", "question", "key_points")
VALID_DIFFICULTY = {"fact", "concept", "synthesis"}
REVIEWED_STATUSES = {"human_reviewed", "approved"}
GENERIC_TERMS = {
    "数据", "内存", "连接", "系统", "服务", "线程", "锁", "消息", "性能",
    "配置", "对象", "查询", "日志", "事务", "网络", "时间", "方法", "结果",
    "操作", "类型", "资源", "状态", "问题", "处理", "异常", "请求", "调用",
}


def load_questions(path: pathlib.Path) -> list[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as handle:
        root = yaml.safe_load(handle)
    if not isinstance(root, dict) or not isinstance(root.get("questions"), list):
        raise ValueError("YAML 根节点必须包含 questions 列表")
    return [q for q in root["questions"] if isinstance(q, dict)]


def non_empty(value: Any) -> bool:
    if isinstance(value, str):
        return bool(value.strip())
    return value not in (None, [], {})


def term_leakage(question: str, key_points: Any) -> int:
    """统计至少有一个 key point 原词出现在问题中的题目数（启发式）。"""
    if not isinstance(key_points, list):
        return 0
    lower_question = question.lower()
    for group in key_points:
        terms = group if isinstance(group, list) else [group]
        for term in terms:
            text = str(term or "").strip()
            # 单字符和通用词命中信息量过低，不作为泄漏告警。
            if len(text) < 2 or text in GENERIC_TERMS:
                continue
            if text.lower() in lower_question:
                return 1
    return 0


def audit(questions: list[dict[str, Any]], require_reviewed_gold: bool) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    warnings: list[str] = []
    ids: list[str] = []
    questions_text: list[str] = []
    source_counts: collections.Counter[str] = collections.Counter()
    difficulty_counts: collections.Counter[str] = collections.Counter()
    type_counts: collections.Counter[str] = collections.Counter()
    split_counts: collections.Counter[str] = collections.Counter()
    leakage = 0
    hard_leakage = 0
    answerable = 0
    reviewed = 0

    for index, item in enumerate(questions):
        label = str(item.get("id") or f"#{index}")
        for field in REQUIRED_FIELDS:
            if not non_empty(item.get(field)):
                errors.append(f"{label}: 缺少字段 {field}")
        ids.append(str(item.get("id") or ""))
        question = str(item.get("question") or "").strip()
        questions_text.append(question)
        source_counts[str(item.get("source") or "unknown")] += 1
        difficulty = str(item.get("difficulty") or "")
        difficulty_counts[difficulty or "unknown"] += 1
        if difficulty and difficulty not in VALID_DIFFICULTY:
            errors.append(f"{label}: difficulty 非法: {difficulty}")
        question_type = item.get("question_type")
        if question_type:
            type_counts[str(question_type)] += 1
        split = item.get("split")
        if split:
            split_counts[str(split)] += 1

        key_points = item.get("key_points")
        if not isinstance(key_points, list) or not key_points:
            errors.append(f"{label}: key_points 应为非空列表")
        else:
            for group_index, group in enumerate(key_points):
                terms = group if isinstance(group, list) else [group]
                if not terms or not any(non_empty(term) for term in terms):
                    errors.append(f"{label}: key_points[{group_index}] 为空")
                if len(terms) > 4:
                    warnings.append(f"{label}: key_points[{group_index}] 有 {len(terms)} 个候选，可能混入多个事实/方案")
                if any(str(term or "").strip() in GENERIC_TERMS for term in terms):
                    warnings.append(f"{label}: key_points[{group_index}] 含通用词，可能产生 substring 假命中")

        if item.get("query_hard") and str(item["query_hard"]).strip() == question:
            warnings.append(f"{label}: query_hard 与 question 完全相同")
        leakage += term_leakage(question, key_points)
        hard_leakage += term_leakage(str(item.get("query_hard") or ""), key_points)

        is_answerable = bool(item.get("answerable", True))
        answerable += int(is_answerable)
        reference = non_empty(item.get("reference_answer"))
        status = str(item.get("review_status") or "").strip()
        if status in REVIEWED_STATUSES:
            reviewed += 1
        if is_answerable and require_reviewed_gold:
            if not reference:
                errors.append(f"{label}: 正常可答题缺少 reference_answer")
            if status not in REVIEWED_STATUSES:
                errors.append(f"{label}: review_status={status or '<missing>'}，不是 human_reviewed/approved")
            if not non_empty(item.get("gold_evidence")):
                errors.append(f"{label}: 正常可答题缺少 gold_evidence")
            elif not isinstance(item.get("gold_evidence"), list):
                errors.append(f"{label}: gold_evidence 应为列表")
            elif isinstance(item.get("gold_evidence"), list):
                for evidence_index, evidence in enumerate(item["gold_evidence"]):
                    if not isinstance(evidence, dict) \
                            or not non_empty(evidence.get("doc_id")) \
                            or not non_empty(evidence.get("quote")):
                        errors.append(f"{label}: gold_evidence[{evidence_index}] 至少需要 doc_id 和 quote")
            for field in ("split", "origin", "question_type"):
                if not non_empty(item.get(field)):
                    errors.append(f"{label}: 正式生成集缺少 {field}")
        if not is_answerable and item.get("expected_refusal") is not True:
            warnings.append(f"{label}: answerable=false 但 expected_refusal 不是 true")

    duplicate_ids = [value for value, count in collections.Counter(ids).items() if value and count > 1]
    duplicate_questions = [value for value, count in collections.Counter(questions_text).items()
                           if value and count > 1]
    if duplicate_ids:
        errors.append(f"重复 id: {', '.join(duplicate_ids)}")
    if duplicate_questions:
        errors.append(f"重复 question: {len(duplicate_questions)} 条")
    if questions and answerable == len(questions):
        warnings.append("全是 answerable=true，没有无答案/部分覆盖题，无法评估拒答")
    if not reviewed:
        warnings.append("没有任何 review_status=human_reviewed/approved 的题")
    if not split_counts:
        warnings.append("没有 split，调参集与 holdout 集无法审计")
    elif "holdout" not in split_counts:
        warnings.append("没有 holdout split，存在调参过拟合风险")
    if not type_counts:
        warnings.append("没有 question_type，无法证明题型覆盖")
    if not any(non_empty(item.get("gold_evidence")) for item in questions):
        warnings.append("没有 gold_evidence，Context Recall 只能依赖答案/关键词代理")
    if questions and leakage:
        warnings.append(f"有 {leakage}/{len(questions)} 题在 question 中直接出现 key point 原词（启发式，需人工复核）")
    if questions and hard_leakage:
        warnings.append(f"有 {hard_leakage}/{len(questions)} 题在 query_hard 中仍直接出现 key point 原词（启发式，不能算完全脱敏改写）")

    return errors, warnings


def main() -> int:
    parser = argparse.ArgumentParser(description="RAG 评测集质量审计")
    parser.add_argument("--dataset", required=True, help="评测集 YAML 路径")
    parser.add_argument("--require-reviewed-gold", action="store_true",
                        help="正式生成集门禁：要求人工审核答案、证据和分层元数据")
    parser.add_argument("--fail-on-warning", action="store_true",
                        help="把质量 warning 也作为非零退出")
    args = parser.parse_args()

    path = pathlib.Path(args.dataset)
    if not path.exists():
        print(f"[audit][ERROR] 文件不存在: {path}", file=sys.stderr)
        return 1
    try:
        questions = load_questions(path)
    except (OSError, ValueError, yaml.YAMLError) as exc:
        print(f"[audit][ERROR] 无法读取数据集: {exc}", file=sys.stderr)
        return 1
    errors, warnings = audit(questions, args.require_reviewed_gold)

    print(f"[audit] dataset={path} questions={len(questions)}")
    print(f"[audit] source={dict(collections.Counter(str(q.get('source') or 'unknown') for q in questions))}")
    print(f"[audit] difficulty={dict(collections.Counter(str(q.get('difficulty') or 'unknown') for q in questions))}")
    print(f"[audit] answerable={sum(bool(q.get('answerable', True)) for q in questions)}/{len(questions)}")
    print(f"[audit] reviewed={sum(str(q.get('review_status') or '') in REVIEWED_STATUSES for q in questions)}/{len(questions)}")
    if errors:
        print(f"[audit] errors={len(errors)}")
        for error in errors:
            print(f"  ERROR: {error}")
    if warnings:
        print(f"[audit] warnings={len(warnings)}")
        for warning in warnings:
            print(f"  WARN: {warning}")
    if not errors and not warnings:
        print("[audit] OK：未发现结构/质量风险")
    return 1 if errors or (warnings and args.fail_on_warning) else 0


if __name__ == "__main__":
    raise SystemExit(main())
