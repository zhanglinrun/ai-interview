#!/usr/bin/env python3
"""Fix document service/impl package structure after partial migration."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(r"e:\javaproject\ai-interview\backend\src\main\java\com\linrun\interview\document\service")
IMPL = ROOT / "impl"

# impl files whose public class name must end with Impl (filename -> class name)
RENAME_CLASS = {
    "DocumentParseServiceImpl.java": ("DocumentParseService", "DocumentParseServiceImpl"),
    "MarkdownProcessServiceImpl.java": ("MarkdownProcessService", "MarkdownProcessServiceImpl"),
    "MineruProcessServiceImpl.java": ("MineruProcessService", "MineruProcessServiceImpl"),
    "SpreadsheetProcessServiceImpl.java": ("SpreadsheetProcessService", "SpreadsheetProcessServiceImpl"),
}

# Delete from service/ when the same simple name exists under impl/
DELETE_FROM_SERVICE = [
    "DocumentCleanupServiceImpl.java",
    "DocumentProcessServiceImpl.java",
    "KnowledgeDocumentServiceImpl.java",
    "KnowledgeDocumentVersionServiceImpl.java",
    "KnowledgeSegmentServiceImpl.java",
    "VectorStoreServiceImpl.java",
    "TextCleaningService.java",
    "MarkdownHeaderBrotherTextSplitter.java",
    "MarkdownHeaderParentTextSplitter.java",
    "NoOpEmbeddedDocumentExtractor.java",
    "SegmentTextCacheService.java",
    "MineruZipExtractor.java",
    "DocumentIngestionFacade.java",
    "ContentTypeDetectionService.java",
    "KnowledgeBaseListService.java",
    "FileTypeResolver.java",
    "ExcelSplitter.java",
    "KnowledgeBaseAccessService.java",
    "KnowledgeBaseChunkingService.java",
    "KnowledgeBaseCountService.java",
    "FileValidationService.java",
    "Neo4jDomainKnowledgeGraphService.java",
    "OfficialMineruClient.java",
    "DocumentParseTaskService.java",
    "VectorizationTaskService.java",
    "FileHashService.java",
]

# Imports to rewrite across backend
IMPORT_REWRITES = [
    ("com.linrun.interview.document.service.KnowledgeBaseListService",
     "com.linrun.interview.document.service.impl.KnowledgeBaseListService"),
    ("com.linrun.interview.document.service.KnowledgeBaseAccessService",
     "com.linrun.interview.document.service.impl.KnowledgeBaseAccessService"),
    ("com.linrun.interview.document.service.DocumentIngestionFacade",
     "com.linrun.interview.document.service.impl.DocumentIngestionFacade"),
    ("com.linrun.interview.document.service.Neo4jDomainKnowledgeGraphService",
     "com.linrun.interview.document.service.impl.Neo4jDomainKnowledgeGraphService"),
    ("com.linrun.interview.document.service.VectorizationTaskService",
     "com.linrun.interview.document.service.impl.VectorizationTaskService"),
    ("com.linrun.interview.document.service.DocumentParseTaskService",
     "com.linrun.interview.document.service.impl.DocumentParseTaskService"),
    ("com.linrun.interview.document.service.MineruProcessService",
     "com.linrun.interview.document.service.impl.MineruProcessServiceImpl"),
    ("com.linrun.interview.document.service.MarkdownHeaderBrotherTextSplitter",
     "com.linrun.interview.document.service.impl.MarkdownHeaderBrotherTextSplitter"),
    ("com.linrun.interview.document.service.TextCleaningService",
     "com.linrun.interview.document.service.impl.TextCleaningServiceImpl"),
]

TEXT_CLEANING_INTERFACE = """package com.linrun.interview.document.service;

/**
 * 文本清理服务：RAG/解析前的语义去噪与格式规范化。
 */
public interface TextCleaningService {

    String cleanText(String text);

    String cleanTextWithLimit(String text, int maxLength);

    String cleanToSingleLine(String text);

    String stripHtml(String text);
}
"""


def fix_impl_packages() -> None:
    for path in IMPL.glob("*.java"):
        text = path.read_text(encoding="utf-8")
        if "package com.linrun.interview.document.service;" in text:
            text = text.replace(
                "package com.linrun.interview.document.service;",
                "package com.linrun.interview.document.service.impl;",
                1,
            )
        if path.name in RENAME_CLASS:
            old, new = RENAME_CLASS[path.name]
            text = re.sub(rf"public class {old}\b", f"public class {new}", text, count=1)
            # fix self-references in same file
            if old != new:
                text = text.replace(f"{old}(", f"{new}(")
        # impl classes should import service-layer interfaces from parent package
        text = text.replace(
            "import com.linrun.interview.document.service.impl.TextCleaningService;",
            "import com.linrun.interview.document.service.TextCleaningService;",
        )
        text = text.replace(
            "import com.linrun.interview.document.service.impl.DocumentParseService;",
            "import com.linrun.interview.document.service.DocumentParseService;",
        )
        path.write_text(text, encoding="utf-8", newline="\n")
        print("package/class", path.name)


def delete_duplicates() -> None:
    for name in DELETE_FROM_SERVICE:
        path = ROOT / name
        if path.exists():
            path.unlink()
            print("deleted", name)


def write_text_cleaning_interface() -> None:
    (ROOT / "TextCleaningService.java").write_text(TEXT_CLEANING_INTERFACE, encoding="utf-8", newline="\n")
    print("wrote TextCleaningService interface")


def rewrite_imports() -> None:
    backend = Path(r"e:\javaproject\ai-interview\backend\src\main\java")
    for path in backend.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        original = text
        for old, new in IMPORT_REWRITES:
            text = text.replace(old, new)
        if text != original:
            path.write_text(text, encoding="utf-8", newline="\n")
            print("imports", path.relative_to(backend))


def main() -> None:
    fix_impl_packages()
    delete_duplicates()
    write_text_cleaning_interface()
    rewrite_imports()


if __name__ == "__main__":
    main()
