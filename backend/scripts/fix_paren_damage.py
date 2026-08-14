#!/usr/bin/env python3
"""Fix Java files damaged by accidental removal of () during comment cleanup."""
import re
from pathlib import Path

ROOT = Path(r"e:\javaproject\ai-interview\backend\src\main\java\com\linrun\interview\document")

FILES = [
    ROOT / "entity/TableMetaEntity.java",
    ROOT / "service/impl/ExcelProcessServiceImpl.java",
    ROOT / "service/impl/MineruMarkdownImageRewriter.java",
    ROOT / "service/impl/ImageDescriptionService.java",
    ROOT / "service/impl/TextCleaningServiceImpl.java",
]

REPLACEMENTS = [
    (r"public (\w+(?:<[^>]+>)?) get(\w+) \{", r"public \1 get\2() {"),
    (r"new ArrayList<>;", "new ArrayList<>();"),
    (r"new HashSet<>;", "new HashSet<>();"),
    (r"new StringBuilder;", "new StringBuilder();"),
    (r"new TableMetaEntity;", "new TableMetaEntity();"),
    (r"new ReadListener<Map<Integer, String>> \{", "new ReadListener<Map<Integer, String>>() {"),
    (r"Collections\.emptyList(?!\()", "Collections.emptyList()"),
    (r"LocalDateTime\.now(?!\()", "LocalDateTime.now()"),
    (r"\.isBlank(?!\()", ".isBlank()"),
    (r"\.isEmpty(?!\()", ".isEmpty()"),
    (r"\.getFirst(?!\()", ".getFirst()"),
    (r"\.getColumnsInfo(?!\()", ".getColumnsInfo()"),
    (r"\.keySet\.stream", ".keySet().stream()"),
    (r"\.headRowNumber\(0\)\.sheet\.doRead;", ".headRowNumber(0).sheet().doRead();"),
    (r"\.toLowerCase(?!\()", ".toLowerCase()"),
    (r"\.trim(?!\()", ".trim()"),
    (r"\.strip(?!\()", ".strip()"),
    (r"\.toString(?!\()", ".toString()"),
    (r"TABLE_PREFIX\.length(?!\()", "TABLE_PREFIX.length()"),
    (r"userPrefix\.length(?!\()", "userPrefix.length()"),
    (r"excelData\.size(?!\()", "excelData.size()"),
    (r"existingColumns\.size(?!\()", "existingColumns.size()"),
    (r"newColumns\.size(?!\()", "newColumns.size()"),
    (r"headers\.size(?!\()", "headers.size()"),
    (r"rows\.size(?!\()", "rows.size()"),
    (r"dataRows\.size(?!\()", "dataRows.size()"),
    (r"batch\.size(?!\()", "batch.size()"),
    (r"result\.size(?!\()", "result.size()"),
    (r"columns\.size(?!\()", "columns.size()"),
    (r"row\.size(?!\()", "row.size()"),
    (r"text\.length(?!\()", "text.length()"),
    (r"cleaned\.length(?!\()", "cleaned.length()"),
    (r"baseName\.length(?!\()", "baseName.length()"),
    (r"sanitized\.length(?!\()", "sanitized.length()"),
    (r"cell\.toString\.trim\(\)", "cell.toString().trim()"),
    (r"row\.stream\.anyMatch", "row.stream().anyMatch"),
    (r"columns\.stream(?!\()", "columns.stream()"),
    (r"\.matches(?!\()", ".matches()"),
    (r"matcher\.find(?!\()", "matcher.find()"),
    (r"getFileName\.toString(?!\()", "getFileName().toString()"),
    (r"imageUrlByFileName\.isEmpty(?!\()", "imageUrlByFileName.isEmpty()"),
    (r"request\.userId(?!\()", "request.userId()"),
    (r"request\.versionId(?!\()", "request.versionId()"),
    (r"request\.fileBytes(?!\()", "request.fileBytes()"),
    (r"request\.fileName(?!\()", "request.fileName()"),
    (r"e\.getMessage(?!\()", "e.getMessage()"),
    (r"Wrappers\.<TableMetaEntity>lambdaQuery(?!\()", "Wrappers.<TableMetaEntity>lambdaQuery()"),
    (r"a\.columnName(?!\()", "a.columnName()"),
    (r"b\.columnName(?!\()", "b.columnName()"),
    (r"a\.dataType(?!\()", "a.dataType()"),
    (r"b\.dataType(?!\()", "b.dataType()"),
    (r"column\.columnName(?!\()", "column.columnName()"),
    (r"column\.dataType(?!\()", "column.dataType()"),
    (r"column\.originalHeader(?!\()", "column.originalHeader()"),
    (r"mineruProperties\.isVisionAltEnabled(?!\()", "mineruProperties.isVisionAltEnabled()"),
    (r"mineruProperties\.getVisionModel(?!\()", "mineruProperties.getVisionModel()"),
    (r"chatModel\.chat\(message\)\.aiMessage\.text(?!\()", "chatModel.chat(message).aiMessage().text()"),
]

for path in FILES:
    if not path.exists():
        print("skip missing", path)
        continue
    text = path.read_text(encoding="utf-8")
    for pat, rep in REPLACEMENTS:
        text = re.sub(pat, rep, text)
    path.write_text(text, encoding="utf-8", newline="\n")
    print("fixed", path.name)
