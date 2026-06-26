package com.linrun.interview.modules.knowledgebase.service;

import com.linrun.interview.modules.knowledgebase.constant.DocumentStatus;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseVersionEntity;
import com.linrun.interview.modules.knowledgebase.repository.KnowledgeBaseVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 知识库版本 Service 实现（对齐 know-engine KnowledgeDocumentVersionServiceImpl）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentVersionServiceImpl implements KnowledgeDocumentVersionService {

    private final KnowledgeBaseVersionRepository versionRepository;

    @Override
    @Transactional
    public KnowledgeBaseVersionEntity save(KnowledgeBaseVersionEntity version) {
        return versionRepository.save(version);
    }

    @Override
    public Optional<KnowledgeBaseVersionEntity> getById(Long versionId) {
        return versionRepository.findById(versionId);
    }

    @Override
    public List<KnowledgeBaseVersionEntity> listByDocId(Long docId) {
        return versionRepository.findByDocIdOrderByVersionIdDesc(docId);
    }

    @Override
    public Optional<KnowledgeBaseVersionEntity> findLatestByDocId(Long docId) {
        return versionRepository.findFirstByDocIdOrderByVersionIdDesc(docId);
    }

    @Override
    public Optional<KnowledgeBaseVersionEntity> findByContentHash(String contentHash) {
        return versionRepository.findByContentHash(contentHash);
    }

    @Override
    public Optional<KnowledgeBaseVersionEntity> findByDocIdAndVersion(Long docId, String version) {
        return versionRepository.findByDocIdAndVersion(docId, version);
    }

    @Override
    @Transactional
    public void update(KnowledgeBaseVersionEntity version) {
        versionRepository.save(version);
    }

    @Override
    @Transactional
    public int physicalDeleteByDocId(Long docId) {
        int deleted = versionRepository.physicalDeleteByDocId(docId);
        log.info("按docId物理删除版本: docId={}, deleted={}", docId, deleted);
        return deleted;
    }

    @Override
    @Transactional
    public int physicalDeleteByVersionId(Long versionId) {
        int deleted = versionRepository.physicalDeleteByVersionId(versionId);
        log.info("按versionId物理删除版本: versionId={}, deleted={}", versionId, deleted);
        return deleted;
    }

    @Override
    public List<KnowledgeBaseVersionEntity> findByStatus(DocumentStatus status) {
        return versionRepository.findByStatus(status);
    }
}
