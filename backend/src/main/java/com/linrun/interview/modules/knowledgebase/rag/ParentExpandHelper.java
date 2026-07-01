package com.linrun.interview.modules.knowledgebase.rag;

import com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseSegmentEntity;
import dev.langchain4j.data.document.Metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 父子/兄弟分段扩展文本拼接（供 {@link InterviewElasticsearchContentRetriever} 与单测复用）。
 */
public final class ParentExpandHelper {

  private ParentExpandHelper() {
  }

  public static String buildExpandedText(String hitText,
                                         Metadata meta,
                                         Map<String, String> parentTextByChunkId,
                                         Map<String, List<KnowledgeBaseSegmentEntity>> brothersByGroupId,
                                         int maxChars,
                                         int maxSiblings,
                                         String strategy) {
    String pid = meta.getString(MetadataKeyConstant.PARENT_CHUNK_ID);
    String parentText = pid != null ? parentTextByChunkId.get(pid) : null;
    if ("replace".equalsIgnoreCase(strategy) && parentText != null && !parentText.isBlank()) {
      return truncateToMax(parentText, maxChars);
    }

    StringBuilder sb = new StringBuilder();
    appendTruncated(sb, hitText, maxChars);

    String bid = meta.getString(MetadataKeyConstant.BROTHER_CHUNK_ID);
    List<KnowledgeBaseSegmentEntity> brothers = bid != null ? brothersByGroupId.get(bid) : null;
    if (brothers != null && brothers.size() > 1) {
      int added = 1;
      for (KnowledgeBaseSegmentEntity b : nearbyBrothers(brothers, meta, maxSiblings)) {
        if (added >= maxSiblings) {
          break;
        }
        if (!isHitBrother(b, meta) && b.getText() != null && !b.getText().isBlank()) {
          appendTruncated(sb, b.getText(), maxChars);
          added++;
        }
      }
    }

    if (pid != null && parentText != null && !parentText.isBlank()) {
      appendTruncated(sb, parentText, maxChars);
    }
    return sb.toString();
  }

  static void appendTruncated(StringBuilder sb, String text, int maxChars) {
    if (sb.length() >= maxChars) {
      return;
    }
    if (sb.length() > 0) {
      sb.append("\n\n");
    }
    int remaining = maxChars - sb.length();
    sb.append(text, 0, Math.min(text.length(), remaining));
  }

  static String truncateToMax(String text, int maxChars) {
    if (text.length() <= maxChars) {
      return text;
    }
    return text.substring(0, maxChars);
  }

  private static List<KnowledgeBaseSegmentEntity> nearbyBrothers(
      List<KnowledgeBaseSegmentEntity> brothers, Metadata meta, int maxSiblings) {
    int hitIndex = findHitBrotherIndex(brothers, meta);
    if (hitIndex < 0 || maxSiblings <= 1) {
      return List.of();
    }
    int maxNeighbors = maxSiblings == Integer.MAX_VALUE ? Integer.MAX_VALUE : maxSiblings - 1;
    List<KnowledgeBaseSegmentEntity> nearby = new ArrayList<>();
    int left = hitIndex - 1;
    int right = hitIndex + 1;
    while (nearby.size() < maxNeighbors && (left >= 0 || right < brothers.size())) {
      if (right < brothers.size()) {
        nearby.add(brothers.get(right++));
      }
      if (nearby.size() >= maxNeighbors) {
        break;
      }
      if (left >= 0) {
        nearby.add(brothers.get(left--));
      }
    }
    return nearby;
  }

  private static int findHitBrotherIndex(List<KnowledgeBaseSegmentEntity> brothers, Metadata meta) {
    String hitChunkId = meta.getString(MetadataKeyConstant.CHUNK_ID);
    Integer hitBrotherIndex = meta.getInteger(MetadataKeyConstant.BROTHER_CHUNK_INDEX);
    for (int i = 0; i < brothers.size(); i++) {
      KnowledgeBaseSegmentEntity brother = brothers.get(i);
      if (hitChunkId != null && hitChunkId.equals(brother.getChunkId())) {
        return i;
      }
      if (hitBrotherIndex != null && hitBrotherIndex.equals(brother.getBrotherChunkIndex())) {
        return i;
      }
    }
    return -1;
  }

  private static boolean isHitBrother(KnowledgeBaseSegmentEntity brother, Metadata meta) {
    String hitChunkId = meta.getString(MetadataKeyConstant.CHUNK_ID);
    if (hitChunkId != null && hitChunkId.equals(brother.getChunkId())) {
      return true;
    }
    Integer hitBrotherIndex = meta.getInteger(MetadataKeyConstant.BROTHER_CHUNK_INDEX);
    return hitBrotherIndex != null && hitBrotherIndex.equals(brother.getBrotherChunkIndex());
  }
}
