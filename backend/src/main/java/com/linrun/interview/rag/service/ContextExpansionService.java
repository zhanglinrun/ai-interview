package com.linrun.interview.rag.service;
import com.linrun.interview.rag.service.ParentExpandHelper;

import com.linrun.interview.rag.constant.MetadataKeyConstant;
import com.linrun.interview.document.entity.KnowledgeBaseSegmentEntity;
import com.linrun.interview.rag.config.KnowledgeBaseQueryProperties;
import com.linrun.interview.document.service.KnowledgeSegmentService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Small-to-big 上下文扩展。
 *
 * <p>该服务只接收已经完成召回融合和 rerank 的小块，随后查询父块或同组兄弟块，避免大块正文
 * 提前参与精排而稀释命中片段的相关性。
 */
public class ContextExpansionService {

  private final KnowledgeSegmentService segmentService;
  private final KnowledgeBaseQueryProperties.ParentExpand properties;

  public ContextExpansionService(
      KnowledgeSegmentService segmentService,
      KnowledgeBaseQueryProperties.ParentExpand properties
  ) {
    this.segmentService = segmentService;
    this.properties = properties;
  }

  public List<Content> expand(List<Content> hits) {
    if (hits == null || hits.isEmpty() || segmentService == null
        || properties == null || !properties.isEnabled()) {
      return hits == null ? List.of() : hits;
    }

    Set<String> parentChunkIds = new HashSet<>();
    Set<String> brotherChunkIds = new HashSet<>();
    for (Content hit : hits) {
      Metadata metadata = hit.textSegment().metadata();
      addIfPresent(parentChunkIds, metadata.getString(MetadataKeyConstant.PARENT_CHUNK_ID));
      addIfPresent(brotherChunkIds, metadata.getString(MetadataKeyConstant.BROTHER_CHUNK_ID));
    }

    Map<String, List<KnowledgeBaseSegmentEntity>> parentsByChunkId = new HashMap<>();
    if (!parentChunkIds.isEmpty()) {
      for (KnowledgeBaseSegmentEntity segment
          : segmentService.findByChunkIdIn(new ArrayList<>(parentChunkIds))) {
        parentsByChunkId.computeIfAbsent(segment.getChunkId(), key -> new ArrayList<>()).add(segment);
      }
    }

    Map<String, List<KnowledgeBaseSegmentEntity>> brothersByGroupId = new LinkedHashMap<>();
    if (!brotherChunkIds.isEmpty()) {
      for (KnowledgeBaseSegmentEntity segment
          : segmentService.findByBrotherChunkIdIn(new ArrayList<>(brotherChunkIds))) {
        brothersByGroupId.computeIfAbsent(segment.getBrotherChunkId(), key -> new ArrayList<>())
            .add(segment);
      }
    }

    int maxChars = properties.getMaxChars() > 0 ? properties.getMaxChars() : Integer.MAX_VALUE;
    int maxSiblings = properties.getMaxSiblings() > 0
        ? properties.getMaxSiblings() : Integer.MAX_VALUE;
    List<Content> expanded = new ArrayList<>(hits.size());
    Set<String> seen = new HashSet<>();
    Set<String> appendedParents = new HashSet<>();
    boolean appendStrategy = !"replace".equalsIgnoreCase(properties.getStrategy());
    for (Content hit : hits) {
      Metadata metadata = hit.textSegment().metadata();
      String parentId = metadata.getString(MetadataKeyConstant.PARENT_CHUNK_ID);
      String brotherId = metadata.getString(MetadataKeyConstant.BROTHER_CHUNK_ID);

      Map<String, String> parentTexts = new HashMap<>();
      KnowledgeBaseSegmentEntity parent = findSameScope(parentsByChunkId.get(parentId), metadata);
      if (parent != null && parent.getText() != null && !parent.getText().isBlank()) {
        String parentKey = scopeKey(metadata) + "|parent|" + parentId;
        if (!appendStrategy || appendedParents.add(parentKey)) {
          parentTexts.put(parentId, parent.getText());
        }
      }

      Map<String, List<KnowledgeBaseSegmentEntity>> scopedBrothers = new LinkedHashMap<>();
      List<KnowledgeBaseSegmentEntity> brothers = filterSameScope(
          brothersByGroupId.get(brotherId), metadata);
      if (!brothers.isEmpty()) {
        scopedBrothers.put(brotherId, brothers);
      }

      TextSegment segment = hit.textSegment();
      String expandedText = ParentExpandHelper.buildExpandedText(
          segment.text(), metadata, parentTexts, scopedBrothers, maxChars, maxSiblings,
          properties.getStrategy());
      Content result = expandedText.equals(segment.text())
          ? hit
          : Content.from(new TextSegment(expandedText, metadata.put("expanded", "1")), hit.metadata());
      String dedupKey = scopeKey(metadata) + "|" + expandedText;
      if (seen.add(dedupKey)) {
        expanded.add(result);
      }
    }
    return expanded;
  }

  private KnowledgeBaseSegmentEntity findSameScope(
      List<KnowledgeBaseSegmentEntity> candidates,
      Metadata metadata
  ) {
    if (candidates == null) {
      return null;
    }
    return candidates.stream().filter(segment -> matchesScope(segment, metadata)).findFirst().orElse(null);
  }

  private List<KnowledgeBaseSegmentEntity> filterSameScope(
      List<KnowledgeBaseSegmentEntity> candidates,
      Metadata metadata
  ) {
    if (candidates == null) {
      return List.of();
    }
    return candidates.stream().filter(segment -> matchesScope(segment, metadata)).toList();
  }

  private boolean matchesScope(KnowledgeBaseSegmentEntity segment, Metadata metadata) {
    String owner = metadata.getString(MetadataKeyConstant.OWNER_USER_ID);
    String domain = metadata.getString(MetadataKeyConstant.DATA_DOMAIN);
    String resourceId = metadata.getString(MetadataKeyConstant.RESOURCE_ID);
    String resourceVersion = metadata.getString(MetadataKeyConstant.RESOURCE_VERSION);
    if (owner != null && domain != null && resourceId != null && resourceVersion != null) {
      return Objects.equals(owner, String.valueOf(segment.getUserId()))
          && segment.getDataDomain() != null
          && Objects.equals(domain, segment.getDataDomain().name())
          && Objects.equals(resourceId, segment.getResourceId())
          && Objects.equals(resourceVersion, segment.getResourceVersion());
    }
    return Objects.equals(metadata.getString(MetadataKeyConstant.DOC_ID),
        String.valueOf(segment.getDocumentId()))
        && Objects.equals(metadata.getString(MetadataKeyConstant.VERSION),
        String.valueOf(segment.getDocumentVersion()));
  }

  private String scopeKey(Metadata metadata) {
    String owner = value(metadata, MetadataKeyConstant.OWNER_USER_ID);
    String domain = value(metadata, MetadataKeyConstant.DATA_DOMAIN);
    String resourceId = value(metadata, MetadataKeyConstant.RESOURCE_ID);
    String resourceVersion = value(metadata, MetadataKeyConstant.RESOURCE_VERSION);
    if (!owner.isBlank() && !domain.isBlank()
        && !resourceId.isBlank() && !resourceVersion.isBlank()) {
      return String.join("|", owner, domain, resourceId, resourceVersion);
    }
    return String.join("|", "legacy",
        value(metadata, MetadataKeyConstant.DOC_ID),
        value(metadata, MetadataKeyConstant.VERSION));
  }

  private String value(Metadata metadata, String key) {
    String value = metadata.getString(key);
    return value == null ? "" : value;
  }

  private void addIfPresent(Set<String> target, String value) {
    if (value != null && !value.isBlank()) {
      target.add(value);
    }
  }
}
