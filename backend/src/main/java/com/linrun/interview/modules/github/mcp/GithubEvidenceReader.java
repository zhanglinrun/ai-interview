package com.linrun.interview.modules.github.mcp;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.github.config.GithubEvidenceProperties;
import com.linrun.interview.modules.github.model.GithubCodeEvidenceEntity;
import com.linrun.interview.modules.github.model.GithubRepositoryEntity;
import com.linrun.interview.modules.github.model.GithubRepositoryFileEntity;
import com.linrun.interview.modules.github.security.GithubPathPolicy;
import com.linrun.interview.modules.github.service.GithubHashing;
import com.linrun.interview.modules.github.service.GithubRepositoryPersistenceService;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 面试中按需取证：MCP 成功且正文哈希与固定快照一致才采用，否则自动回退快照。 */
@Slf4j
@Service
public class GithubEvidenceReader {

  private final GithubEvidenceProperties properties;
  private final GithubReadOnlyMcpClient mcpClient;
  private final GithubMcpWhitelist whitelist;
  private final GithubRepositoryPersistenceService persistenceService;

  public GithubEvidenceReader(
      GithubEvidenceProperties properties,
      GithubReadOnlyMcpClient mcpClient,
      GithubMcpWhitelist whitelist,
      GithubRepositoryPersistenceService persistenceService
  ) {
    this.properties = properties;
    this.mcpClient = mcpClient;
    this.whitelist = whitelist;
    this.persistenceService = persistenceService;
  }

  public ReadResult readFile(
      Long userId,
      Long repositoryId,
      String commitSha,
      String path
  ) {
    if (!GithubPathPolicy.isSafe(path)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "GitHub 文件路径非法");
    }
    GithubRepositoryEntity repository = persistenceService.requireRepository(userId, repositoryId);
    if (!repository.getFixedCommitSha().equalsIgnoreCase(commitSha)) {
      throw new BusinessException(ErrorCode.FORBIDDEN, "只能读取仓库绑定的固定 Commit SHA");
    }
    GithubRepositoryFileEntity file = persistenceService.findFile(
        userId, repositoryId, commitSha, path);
    GithubMcpRequest mcpRequest = new GithubMcpRequest(
        "get_file_contents",
        repository.getOwnerName(),
        repository.getRepositoryName(),
        commitSha,
        path);
    whitelist.validate(mcpRequest, repository, file);

    if (properties.isMcpEnabled()) {
      try {
        Optional<String> result = mcpClient.execute(mcpRequest);
        if (result.isPresent()
            && file.getContentHash().equals(GithubHashing.sha256(result.get()))) {
          return new ReadResult(result.get(), "MCP", commitSha, path, 1, true);
        }
      } catch (Exception e) {
        log.warn("GitHub MCP 读取失败，回退固定快照: repositoryId={}", repositoryId, e);
      }
    }
    if (file.getContentSnapshot() == null) {
      throw new BusinessException(ErrorCode.GITHUB_EVIDENCE_NOT_FOUND,
          "固定 SHA 文件快照不存在");
    }
    return new ReadResult(file.getContentSnapshot(), "SNAPSHOT", commitSha, path, 1, true);
  }

  /**
   * 按题目冻结的 evidenceId 复核最小代码片段。MCP 仍只读取完整白名单文件，哈希校验通过后
   * 才截取原始行号范围；调用方不会把仓库中的其他文件或整仓源码送入模型。
   */
  public ReadResult readEvidence(
      Long userId,
      Long repositoryId,
      String commitSha,
      String evidenceId
  ) {
    GithubCodeEvidenceEntity evidence = persistenceService.findEvidence(
        userId, repositoryId, commitSha, evidenceId);
    if (evidence == null) {
      throw new BusinessException(ErrorCode.GITHUB_EVIDENCE_NOT_FOUND,
          "固定 SHA 代码证据不存在");
    }
    ReadResult file = readFile(userId, repositoryId, commitSha, evidence.getPath());
    int startLine = Math.max(1, evidence.getStartLine() == null ? 1 : evidence.getStartLine());
    int endLine = Math.max(startLine,
        evidence.getEndLine() == null ? startLine : evidence.getEndLine());
    return new ReadResult(
        sliceLines(file.content(), startLine, endLine),
        file.source(),
        file.commitSha(),
        file.path(),
        startLine,
        true);
  }

  private String sliceLines(String content, int startLine, int endLine) {
    String[] lines = content.split("\\R", -1);
    int from = Math.min(lines.length, startLine - 1);
    int to = Math.min(lines.length, endLine);
    if (from >= to) {
      return "";
    }
    return String.join("\n", java.util.Arrays.copyOfRange(lines, from, to));
  }

  public record ReadResult(
      String content,
      String source,
      String commitSha,
      String path,
      int startLine,
      boolean untrusted
  ) {
  }
}
