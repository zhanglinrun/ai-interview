#!/usr/bin/env python3
"""Add missing service-layer imports to document.service.impl classes."""
import re
from pathlib import Path

IMPL = Path(r"e:\javaproject\ai-interview\backend\src\main\java\com\linrun\interview\document\service\impl")

SERVICE_TYPES = [
    "DocumentCleanupService",
    "DocumentProcessService",
    "KnowledgeDocumentService",
    "KnowledgeDocumentVersionService",
    "KnowledgeSegmentService",
    "VectorStoreService",
    "DocumentParseService",
    "ExcelProcessService",
    "TableMetaCatalogService",
    "FileProcessService",
    "MineruClient",
    "MineruClientException",
    "TextCleaningService",
    "FileStorageService",
    "FileProcessServiceFactory",
    "DocumentSplitterFactory",
    "EmbeddingBatchPolicy",
    "MarkdownHeaderBrotherTextSplitter",
    "MarkdownHeaderParentTextSplitter",
]

for path in sorted(IMPL.glob("*.java")):
    text = path.read_text(encoding="utf-8")
    class_name = path.stem
    needed = []
    for typ in SERVICE_TYPES:
        if typ == class_name or typ + "Impl" == class_name:
            continue
        if re.search(rf"\b{typ}\b", text):
            imp = f"import com.linrun.interview.document.service.{typ};"
            if imp not in text:
                needed.append(imp)
    if not needed:
        continue
    # remove wrong self-import from botched rewrite
    text = re.sub(
        r"import com\.linrun\.interview\.document\.service\.impl\.TextCleaningServiceImpl;\n",
        "",
        text,
    )
    block = "\n".join(sorted(set(needed))) + "\n"
    if re.search(r"^import ", text, flags=re.M):
        text = re.sub(r"(^import .+\n)", block + r"\1", text, count=1)
    else:
        text = re.sub(
            r"(package com\.linrun\.interview\.document\.service\.impl;\n)",
            r"\1\n" + block,
            text,
            count=1,
        )
    path.write_text(text, encoding="utf-8", newline="\n")
    print(path.name, "->", len(needed), "imports")
