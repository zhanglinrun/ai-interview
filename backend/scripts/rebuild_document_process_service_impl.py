#!/usr/bin/env python3
"""Rebuild DocumentProcessServiceImpl from git HEAD with DATA_QUERY + previewData patches."""
import subprocess
from pathlib import Path

ROOT = Path(r"e:\javaproject\ai-interview")
OUT = ROOT / "backend/src/main/java/com/linrun/interview/document/service/impl/DocumentProcessServiceImpl.java"


def git_show(path: str) -> str:
    raw = subprocess.check_output(["git", "show", f"HEAD:{path}"], cwd=ROOT)
    if raw.startswith(b"\xef\xbb\xbf"):
        raw = raw[3:]
    return raw.decode("utf-8")


content = git_show("backend/src/main/java/com/linrun/interview/document/service/DocumentProcessServiceImpl.java")
content = content.replace(
    "package com.linrun.interview.document.service;",
    "package com.linrun.interview.document.service.impl;",
)
content = content.replace(
    "import com.linrun.interview.document.service.ContentTypeDetectionService;\n"
    "import com.linrun.interview.document.service.FileHashService;\n"
    "import com.linrun.interview.document.service.FileStorageService;\n"
    "import com.linrun.interview.document.service.FileValidationService;",
    "import com.linrun.interview.document.service.impl.ContentTypeDetectionService;\n"
    "import com.linrun.interview.document.service.impl.FileHashService;\n"
    "import com.linrun.interview.document.service.FileStorageService;\n"
    "import com.linrun.interview.document.service.impl.FileValidationService;\n"
    "import com.linrun.interview.document.service.DocumentProcessService;\n"
    "import com.linrun.interview.document.service.KnowledgeDocumentService;\n"
    "import com.linrun.interview.document.service.KnowledgeDocumentVersionService;\n"
    "import com.linrun.interview.document.service.KnowledgeSegmentService;\n"
    "import com.linrun.interview.document.service.VectorStoreService;\n"
    "import com.linrun.interview.document.service.impl.KnowledgeBaseChunkingService;\n"
    "import com.linrun.interview.document.service.impl.VectorizationTaskService;\n"
    "import com.linrun.interview.document.constant.KnowledgeBaseType;\n"
    "import com.linrun.interview.document.service.ExcelProcessService;",
)
content = content.replace(
    "import com.linrun.interview.document.service.FileTypeResolver;\n"
    "import com.linrun.interview.document.service.ExcelSplitter;",
    "import com.linrun.interview.document.service.impl.FileTypeResolver;\n"
    "import com.linrun.interview.document.service.impl.ExcelSplitter;",
)
content = content.replace(
    "import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;",
    "import com.linrun.interview.document.mapper.KnowledgeBaseEntityMapper;\n"
    "import com.linrun.interview.document.mapper.TableMetaMapper;",
)
content = content.replace(
    "import com.linrun.interview.document.util.DocumentPermissionUtils;",
    "import com.linrun.interview.document.util.DocumentPermissionUtils;\n"
    "import com.linrun.interview.document.util.VersionUtil;\n"
    "import com.baomidou.mybatisplus.extension.plugins.pagination.Page;",
)

content = content.replace(
    "编排 upload（UPLOADED→CONVERTING→CONVERTED）→ 手动 split",
    "编排 upload（UPLOADED→CONVERTING→CONVERTED/STORED）→ 手动 split",
)
content = content.replace(
    "解析产物 Markdown 直接存版本表 {@code convertedContent}（Lob），split 时直接取，省存储往返。",
    "解析产物 Markdown 存版本表 convertedDocUrl 或 convertedContent，split 时直接取。",
)
content = content.replace(
    "知识库文档处理编排实现（对齐业界实践 DocumentProcessServiceImpl）。",
    "知识库文档处理编排实现。",
)

content = content.replace(
    "    private final VectorStoreService vectorStoreService;\n"
    "    private final ApplicationEventPublisher eventPublisher;",
    "    private final VectorStoreService vectorStoreService;\n"
    "    private final ExcelProcessService excelProcessService;\n"
    "    private final TableMetaMapper tableMetaMapper;\n"
    "    private final ApplicationEventPublisher eventPublisher;",
)

old_upload = content[content.index("    public Long upload(MultipartFile file, String title, String category,\n"
                                   "                       DocumentAccessScope accessScope, LocalDate expireDate) {"):]
old_upload = old_upload[:old_upload.index("    @Override\n    @DistributeLock(key = \"'kb:newversion:'")]

new_upload = """    public Long upload(MultipartFile file, String title, String category,
                       DocumentAccessScope accessScope, LocalDate expireDate) {
        return upload(file, title, category, accessScope, expireDate, KnowledgeBaseType.DOCUMENT_SEARCH);
    }

    @Override
    @DistributeLock(key = "'kb:upload:' + T(com.linrun.interview.auth.security.UserContext).requireUserId() + ':' + #file.originalFilename",
        waitTime = 0, leaseTime = 300, message = "同名文件正在上传，请稍后再试")
    public Long upload(MultipartFile file, String title, String category,
                       DocumentAccessScope accessScope, LocalDate expireDate,
                       KnowledgeBaseType knowledgeBaseType) {
        KnowledgeBaseType kbType = knowledgeBaseType != null
            ? knowledgeBaseType : KnowledgeBaseType.DOCUMENT_SEARCH;
        if (kbType == KnowledgeBaseType.DATA_QUERY) {
            String probeName = file.getOriginalFilename();
            FileType fileType = fileTypeResolver.resolve(probeName,
                contentTypeDetectionService.detectContentType(file));
            if (fileType != FileType.EXCEL && fileType != FileType.CSV) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "DATA_QUERY 仅支持 Excel/CSV 文件");
            }
        }
        Long userId = UserContext.requireUserId();
        String fileName = file.getOriginalFilename();

        // 1. 校验
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");
        String contentType = contentTypeDetectionService.detectContentType(file);
        if (!fileValidationService.isKnowledgeBaseMimeType(contentType)
            && !fileValidationService.isMarkdownExtension(fileName)
            && !fileValidationService.isSpreadsheetExtension(fileName)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "不支持的文件类型，仅支持 PDF、DOCX、DOC、TXT、MD、CSV、Excel 等");
        }
        // 2. 内容哈希去重（按用户隔离的跨版本去重；跨用户不互相阻断，也不泄漏他人文档存在性）
        String contentHash = fileHashService.calculateHash(file);
        if (versionService.findByContentHash(contentHash, userId).isPresent()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档内容已存在，请勿重复上传");
        }

        // 3. 存原始文件到 MinIO
        String storageKey = storageService.uploadKnowledgeBase(file);
        String docUrl = storageService.getFileUrl(storageKey);

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                "读取上传文件失败: " + e.getMessage(), e);
        }

        // 4. 落库主表 + 版本（UPLOADED），再同步转换（UPLOADED → CONVERTING → CONVERTED/STORED）
        LocalDateTime now = LocalDateTime.now();
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setUserId(userId);
        entity.setFileHash(contentHash);
        entity.setName(title != null && !title.isBlank() ? title : fileName);
        entity.setCategory(category);
        entity.setOriginalFilename(fileName);
        entity.setFileSize(file.getSize());
        entity.setContentType(contentType);
        entity.setStorageKey(storageKey);
        entity.setStorageUrl(docUrl);
        entity.setUploadedAt(now);
        entity.setAccessibleBy(accessScope != null ? accessScope.name() : DocumentAccessScope.PRIVATE.name());
        entity.setExpireDate(expireDate);
        entity.setDocStatus(DocumentStatus.UPLOADED);
        entity.setKnowledgeBaseType(kbType);
        if (kbType == KnowledgeBaseType.DATA_QUERY) {
            entity.setTableName(excelProcessService.generatePhysicalTableName(userId, fileName));
        }
        entity = MapperUtils.save(knowledgeBaseEntityMapper, entity);

        KnowledgeBaseVersionEntity version = new KnowledgeBaseVersionEntity();
        version.setDocId(entity.getId());
        version.setVersion(INITIAL_VERSION);
        version.setDocUrl(docUrl);
        version.setStorageKey(storageKey);
        version.setContentHash(contentHash);
        version.setStatus(DocumentStatus.UPLOADED);
        version.setUploadUser(String.valueOf(userId));
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        version = versionService.saveVersion(version);

        entity.setCurrentVersionId(version.getVersionId());
        MapperUtils.save(knowledgeBaseEntityMapper, entity);

        convertFile(userId, entity, version.getVersionId(), fileBytes, fileName, contentType,
            storageKey, kbType);
        if (kbType == KnowledgeBaseType.DATA_QUERY) {
            finalizeAfterDataQuery(entity, version);
        } else {
            finalizeAfterConvert(entity, version);
        }

        log.info("知识库上传完成: docId={}, versionId={}, status={}, kbType={}",
            entity.getId(), version.getVersionId(), entity.getDocStatus(), kbType);
        return entity.getId();
    }

"""

if old_upload not in content:
    raise SystemExit("upload block not found")
content = content.replace(old_upload, new_upload)

content = content.replace(
    "        convertFile(userId, docId, version.getVersionId(), fileBytes, fileName, contentType,\n"
    "            storageKey);\n"
    "\n"
    "        // 即时失效旧当前版本",
    "        convertFile(userId, entity, version.getVersionId(), fileBytes, fileName, contentType,\n"
    "            storageKey, entity.getKnowledgeBaseType() != null\n"
    "                ? entity.getKnowledgeBaseType() : KnowledgeBaseType.DOCUMENT_SEARCH);\n"
    "\n"
    "        // 即时失效旧当前版本",
)
content = content.replace(
    "        finalizeAfterConvert(entity, version);\n"
    "\n"
    "        log.info(\"知识库新版本上传完成: docId={}, version={}, 旧版本即时失效已尝试\",\n"
    "            docId, newVersion);",
    "        if (entity.getKnowledgeBaseType() == KnowledgeBaseType.DATA_QUERY) {\n"
    "            finalizeAfterDataQuery(entity, version);\n"
    "        } else {\n"
    "            finalizeAfterConvert(entity, version);\n"
    "        }\n"
    "\n"
    "        log.info(\"知识库新版本上传完成: docId={}, version={}\", docId, newVersion);",
)

content = content.replace(
    "            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, \"知识库不存在: \" + docId));\n"
    "\n"
    "        if (entity.getDocStatus() == DocumentStatus.CHUNKED) {",
    "            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, \"知识库不存在: \" + docId));\n"
    "\n"
    "        if (entity.getKnowledgeBaseType() == KnowledgeBaseType.DATA_QUERY) {\n"
    "            throw new BusinessException(ErrorCode.BAD_REQUEST, \"DATA_QUERY 文档不支持切块\");\n"
    "        }\n"
    "        if (entity.getDocStatus() == DocumentStatus.CHUNKED) {",
)
content = content.replace(
    "            String markdown = version.getConvertedContent();\n"
    "            if (markdown == null || markdown.isBlank()) {",
    "            String markdown = resolveConvertedMarkdown(version);\n"
    "            if (markdown == null || markdown.isBlank()) {",
)

old_convert = """    private String convertFile(Long userId, Long docId, Long versionId, byte[] fileBytes,
                               String fileName, String contentType, String storageKey) {
        knowledgeDocumentService.advanceDocumentAndVersionStatus(docId, versionId, DocumentStatus.CONVERTING);
        try {
            FileType fileType = fileTypeResolver.resolve(fileName, contentType);
            FileProcessService processor = fileProcessServiceFactory.get(fileType);
            String markdown = processor.processDocument(new DocumentParseRequest(
                userId, docId, versionId, fileBytes, fileName, contentType, storageKey));
            if (markdown == null || markdown.isBlank()) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "文件解析结果为空");
            }

            KnowledgeBaseVersionEntity version = versionService.findById(versionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本记录不存在"));
            version.setConvertedContent(markdown);
            versionService.updateVersion(version);

            knowledgeDocumentService.advanceDocumentAndVersionStatus(
                docId, versionId, DocumentStatus.CONVERTED);
            return markdown;
        } catch (BusinessException e) {
            rollbackToUploaded(docId, versionId);
            throw e;
        } catch (Exception e) {
            rollbackToUploaded(docId, versionId);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                "文件解析失败: " + e.getMessage(), e);
        }
    }

    private void finalizeAfterConvert(KnowledgeBaseEntity entity, KnowledgeBaseVersionEntity version) {"""

new_convert = """    private String convertFile(Long userId, KnowledgeBaseEntity entity, Long versionId,
                               byte[] fileBytes, String fileName, String contentType,
                               String storageKey, KnowledgeBaseType kbType) {
        Long docId = entity.getId();
        knowledgeDocumentService.advanceDocumentAndVersionStatus(docId, versionId, DocumentStatus.CONVERTING);
        try {
            FileType fileType = fileTypeResolver.resolve(fileName, contentType);
            FileProcessService processor = fileProcessServiceFactory.get(fileType, kbType);
            String converted = processor.processDocument(new DocumentParseRequest(
                userId, docId, versionId, fileBytes, fileName, contentType, storageKey, kbType));

            if (kbType == KnowledgeBaseType.DATA_QUERY) {
                knowledgeDocumentService.advanceDocumentAndVersionStatus(
                    docId, versionId, DocumentStatus.STORED);
                return null;
            }
            if (converted == null || converted.isBlank()) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "文件解析结果为空");
            }

            KnowledgeBaseVersionEntity version = versionService.findById(versionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "版本记录不存在"));
            if (converted.startsWith("http://") || converted.startsWith("https://")) {
                version.setConvertedDocUrl(converted);
            } else {
                version.setConvertedDocUrl(
                    storageService.uploadConvertedMarkdown(docId, versionId, converted));
            }
            versionService.updateVersion(version);
            knowledgeDocumentService.advanceDocumentAndVersionStatus(
                docId, versionId, DocumentStatus.CONVERTED);
            return converted;
        } catch (BusinessException e) {
            rollbackToUploaded(docId, versionId);
            throw e;
        } catch (Exception e) {
            rollbackToUploaded(docId, versionId);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                "文件解析失败: " + e.getMessage(), e);
        }
    }

    private String resolveConvertedMarkdown(KnowledgeBaseVersionEntity version) {
        if (version.getConvertedDocUrl() != null && !version.getConvertedDocUrl().isBlank()) {
            return new String(
                storageService.downloadConvertedMarkdown(version.getConvertedDocUrl()),
                StandardCharsets.UTF_8);
        }
        return version.getConvertedContent();
    }

    private void finalizeAfterDataQuery(KnowledgeBaseEntity entity, KnowledgeBaseVersionEntity version) {
        entity.setCurrentVersionId(version.getVersionId());
        entity.setDocStatus(DocumentStatus.STORED);
        MapperUtils.save(knowledgeBaseEntityMapper, entity);
    }

    private void finalizeAfterConvert(KnowledgeBaseEntity entity, KnowledgeBaseVersionEntity version) {"""

if old_convert not in content:
    raise SystemExit("convertFile block not found")
content = content.replace(old_convert, new_convert)

# DATA_QUERY switchVersion + previewData before nextVersion
switch_anchor = "        if (!target.getDocId().equals(docId)) {\n"
switch_idx = content.index(switch_anchor, content.index("public void switchVersion"))
insert_after = content.index("        Long currentVersionId = doc.getCurrentVersionId();", switch_idx)
data_query_block = """
        if (doc.getKnowledgeBaseType() == KnowledgeBaseType.DATA_QUERY) {
            if (versionId.equals(doc.getCurrentVersionId())) {
                log.info("DATA_QUERY 目标版本已是当前版本，跳过: docId={}, versionId={}", docId, versionId);
                return;
            }
            KnowledgeBaseVersionEntity latest = versionService.findLatestByDocId(docId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库无版本记录: " + docId));
            if (VersionUtil.compareVersions(target.getVersion(), latest.getVersion()) < 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "DATA_QUERY 类型文档不支持切换到旧版本");
            }
            doc.setCurrentVersionId(versionId);
            MapperUtils.save(knowledgeBaseEntityMapper, doc);
            log.info("DATA_QUERY 版本切换完成: docId={}, versionId={}", docId, versionId);
            return;
        }

"""
content = content[:insert_after] + data_query_block + content[insert_after:]

preview_block = """
    @Override
    public Page<Map<String, Object>> previewData(Long docId, int current, int size) {
        Long userId = UserContext.requireUserId();
        KnowledgeBaseEntity doc = EntityQueries.byUserAndId(
                knowledgeBaseEntityMapper, userId, docId,
                KnowledgeBaseEntity::getUserId, KnowledgeBaseEntity::getId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在: " + docId));
        if (doc.getKnowledgeBaseType() != KnowledgeBaseType.DATA_QUERY) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持数据查询类型文档");
        }
        String physicalTableName = doc.getTableName();
        if (physicalTableName == null || physicalTableName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档未配置数据表名称");
        }
        if (tableMetaMapper.checkTableExists(physicalTableName) <= 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "数据表不存在");
        }
        int pageNo = Math.max(current, 1);
        int pageSize = Math.max(size, 1);
        long total = ((Number) tableMetaMapper.executeQuery(
            "SELECT COUNT(*) AS cnt FROM `" + physicalTableName + "`").getFirst().get("cnt")).longValue();
        Page<Map<String, Object>> page = new Page<>(pageNo, pageSize, total);
        if (total > 0) {
            long offset = (long) (pageNo - 1) * pageSize;
            String querySql = "SELECT * FROM `" + physicalTableName
                + "` ORDER BY id ASC LIMIT " + pageSize + " OFFSET " + offset;
            page.setRecords(tableMetaMapper.executeQuery(querySql));
        }
        return page;
    }

"""
next_version_idx = content.index("    private String nextVersion(String current) {")
content = content[:next_version_idx] + preview_block + content[next_version_idx:]

OUT.write_text(content, encoding="utf-8", newline="\n")
print("Wrote", OUT, "lines", content.count(chr(10)) + 1, "知识", content.count("知识"))
