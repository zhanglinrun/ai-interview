package com.linrun.interview.modules.github.service;

import com.linrun.interview.common.evidence.EvidenceRef;
import com.linrun.interview.common.evidence.EvidenceStatus;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.github.config.GithubEvidenceProperties;
import com.linrun.interview.modules.github.dto.GithubEvidenceCardDTO;
import com.linrun.interview.modules.github.dto.GithubEvidenceCardRequest;
import com.linrun.interview.modules.github.dto.GithubEvidenceCardRequest.CapabilityTarget;
import com.linrun.interview.modules.github.model.GithubCodeEvidenceEntity;
import com.linrun.interview.modules.github.model.GithubRepositoryEntity;
import com.linrun.interview.modules.github.model.GithubRepositorySyncStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 确定性生成“岗位能力 × 固定 SHA 代码证据”卡，证据与候选人能力评分保持分离。 */
@Service
public class GithubEvidenceCardService {

  private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}_-]+");

  private final GithubEvidenceProperties properties;
  private final GithubRepositoryPersistenceService persistenceService;
  private final GithubEvidenceIndexer evidenceIndexer;

  public GithubEvidenceCardService(
      GithubEvidenceProperties properties,
      GithubRepositoryPersistenceService persistenceService,
      GithubEvidenceIndexer evidenceIndexer
  ) {
    this.properties = properties;
    this.persistenceService = persistenceService;
    this.evidenceIndexer = evidenceIndexer;
  }

  public List<GithubEvidenceCardDTO> generate(
      Long userId,
      Long repositoryId,
      GithubEvidenceCardRequest request
  ) {
    GithubRepositoryEntity repository = persistenceService.requireRepository(userId, repositoryId);
    if (repository.getSyncStatus() != GithubRepositorySyncStatus.SYNCED
        && repository.getSyncStatus() != GithubRepositorySyncStatus.PARTIAL
        && repository.getSyncStatus() != GithubRepositorySyncStatus.SOURCE_UNAVAILABLE) {
      throw new BusinessException(ErrorCode.GITHUB_REPOSITORY_NOT_READY,
          "请先完成 GitHub 固定 SHA 证据同步");
    }
    List<GithubCodeEvidenceEntity> chunks = persistenceService.listEvidence(
        userId,
        repositoryId,
        repository.getFixedCommitSha(),
        properties.getMaxEvidenceChunks());
    return request.capabilities().stream()
        .map(capability -> card(repository, chunks, capability, request.evidencePerCapability()))
        .toList();
  }

  private GithubEvidenceCardDTO card(
      GithubRepositoryEntity repository,
      List<GithubCodeEvidenceEntity> chunks,
      CapabilityTarget capability,
      int limit
  ) {
    Set<String> tokens = tokens(capability);
    List<ScoredChunk> matches = chunks.stream()
        .map(chunk -> new ScoredChunk(chunk, score(chunk, tokens)))
        .filter(match -> match.score() > 0)
        .sorted(Comparator.comparingInt(ScoredChunk::score).reversed()
            .thenComparing(match -> match.chunk().getPath())
            .thenComparing(match -> match.chunk().getStartLine()))
        .limit(limit)
        .toList();
    if (matches.isEmpty()) {
      return new GithubEvidenceCardDTO(
          capability.atomId(),
          capability.atomVersion(),
          capability.name(),
          EvidenceStatus.NONE.name(),
          0.0d,
          "当前固定 SHA 快照未找到与该能力直接相关的代码证据；不据此扣分。",
          null,
          List.of());
    }

    int topScore = matches.getFirst().score();
    EvidenceStatus status = topScore >= 8 && matches.size() >= 2
        ? EvidenceStatus.SUFFICIENT : EvidenceStatus.WEAK;
    double confidence = Math.min(0.95d, 0.35d + topScore * 0.05d + matches.size() * 0.05d);
    GithubCodeEvidenceEntity primary = matches.getFirst().chunk();
    List<EvidenceRef> refs = matches.stream()
        .map(match -> EvidenceRef.from(evidenceIndexer.metadata(repository, match.chunk())))
        .toList();
    String question = "我看到项目的 " + primary.getPath() + " 里有 "
        + primary.getSymbolName() + " 这段实现。它在整个业务链路中负责什么？"
        + "当时为什么这样设计？";
    String note = status == EvidenceStatus.SUFFICIENT
        ? "代码路径、符号和能力关键词存在多处相互支持的证据；仍需候选人口述确认贡献边界。"
        : "仅有有限代码线索，将用于中立澄清，不把证据不足解释为能力不足或虚假陈述。";
    return new GithubEvidenceCardDTO(
        capability.atomId(),
        capability.atomVersion(),
        capability.name(),
        status.name(),
        confidence,
        note,
        question,
        refs);
  }

  private int score(GithubCodeEvidenceEntity chunk, Set<String> tokens) {
    String path = lower(chunk.getPath());
    String symbol = lower(chunk.getSymbolName());
    String summary = lower(chunk.getParentSummary());
    String content = lower(chunk.getContent());
    int score = 0;
    for (String token : tokens) {
      if (symbol.contains(token)) {
        score += 6;
      }
      if (path.contains(token)) {
        score += 4;
      }
      if (summary.contains(token)) {
        score += 2;
      }
      if (content.contains(token)) {
        score += 1;
      }
    }
    return score;
  }

  private Set<String> tokens(CapabilityTarget capability) {
    Set<String> tokens = new LinkedHashSet<>();
    List<String> sources = new ArrayList<>();
    sources.add(capability.atomId().replace('_', ' '));
    sources.add(capability.name());
    sources.addAll(capability.keywords());
    for (String source : sources) {
      for (String value : TOKEN_SPLIT.split(lower(source))) {
        if (value.length() >= 2) {
          tokens.add(value);
        }
      }
    }
    return tokens;
  }

  private String lower(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }

  private record ScoredChunk(GithubCodeEvidenceEntity chunk, int score) {
  }
}
