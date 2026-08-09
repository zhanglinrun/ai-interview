package com.linrun.interview.github.service;

import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.rag.model.EvidenceIndexChunk;
import com.linrun.interview.rag.model.EvidenceIndexPort;
import com.linrun.interview.rag.model.EvidenceMetadata;
import com.linrun.interview.github.model.GithubCodeEvidenceEntity;
import com.linrun.interview.github.model.GithubRepositoryEntity;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** GitHub 固定快照到统一证据索引的薄适配器。 */
@Component
public class GithubEvidenceIndexer {

  private final EvidenceIndexPort evidenceIndexPort;

  public GithubEvidenceIndexer(EvidenceIndexPort evidenceIndexPort) {
    this.evidenceIndexPort = evidenceIndexPort;
  }

  public List<String> replace(
      GithubRepositoryEntity repository,
      List<GithubCodeEvidenceEntity> chunks
  ) {
    String resourceId = resourceId(repository.getId());
    evidenceIndexPort.delete(
        repository.getUserId(), DataDomain.GITHUB, resourceId, repository.getFixedCommitSha());
    if (chunks.isEmpty()) {
      return List.of();
    }
    List<EvidenceIndexChunk> indexChunks = chunks.stream()
        .map(chunk -> new EvidenceIndexChunk(
            chunk.getContent(),
            metadata(repository, chunk),
            Map.of(
                "repositoryId", String.valueOf(repository.getId()),
                "commitSha", chunk.getCommitSha(),
                "path", chunk.getPath(),
                "language", chunk.getLanguage(),
                "symbolName", chunk.getSymbolName(),
                "symbolKind", chunk.getSymbolKind(),
                "startLine", String.valueOf(chunk.getStartLine()),
                "endLine", String.valueOf(chunk.getEndLine()),
                "untrustedContent", "true")))
        .toList();
    return evidenceIndexPort.index(indexChunks);
  }

  public void delete(GithubRepositoryEntity repository) {
    evidenceIndexPort.delete(
        repository.getUserId(),
        DataDomain.GITHUB,
        resourceId(repository.getId()),
        repository.getFixedCommitSha());
  }

  public EvidenceMetadata metadata(
      GithubRepositoryEntity repository,
      GithubCodeEvidenceEntity chunk
  ) {
    return new EvidenceMetadata(
        chunk.getOwnerUserId(),
        chunk.getDataDomain(),
        chunk.getResourceId(),
        chunk.getResourceVersion(),
        chunk.getEvidenceId(),
        chunk.getContentHash(),
        "GITHUB_CODE",
        chunk.getSourceLocator());
  }

  public static String resourceId(Long repositoryId) {
    return "github-repository:" + repositoryId;
  }
}
