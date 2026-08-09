package com.linrun.interview.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.rag.model.DataDomain;
import com.linrun.interview.rag.model.EvidenceCandidate;
import com.linrun.interview.rag.model.EvidencePacket;
import com.linrun.interview.rag.model.EvidenceRef;
import com.linrun.interview.rag.model.EvidenceStatus;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.infra.persistence.MapperUtils;
import com.linrun.interview.rag.mapper.EvidenceSnapshotMapper;
import com.linrun.interview.rag.mapper.EvidenceSnapshotRefMapper;
import com.linrun.interview.rag.model.EvidenceSnapshotEntity;
import com.linrun.interview.rag.model.EvidenceSnapshotRefEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 冻结面试所需最小证据片段；源删除后保留快照但标记不可复核。 */
@Service
@RequiredArgsConstructor
public class EvidenceSnapshotService {

  private final EvidenceSnapshotMapper snapshotMapper;
  private final EvidenceSnapshotRefMapper refMapper;
  private final ObjectMapper objectMapper;

  @Transactional(rollbackFor = Exception.class)
  public String save(
      Long userId,
      String contextType,
      String contextId,
      EvidencePacket packet
  ) {
    if (userId == null || userId <= 0 || contextType == null || contextType.isBlank()
        || contextId == null || contextId.isBlank() || packet == null) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "证据快照参数不完整");
    }
    String snapshotId = "evidence-" + UUID.randomUUID();
    EvidenceSnapshotEntity entity = new EvidenceSnapshotEntity();
    entity.setUserId(userId);
    entity.setSnapshotId(snapshotId);
    entity.setContextType(contextType.trim());
    entity.setContextId(contextId.trim());
    entity.setCapabilityAtomKey(packet.capabilityAtomKey());
    entity.setQueryText(packet.query());
    entity.setEvidenceStatus(packet.status());
    entity.setPacketJson(writeJson(packet));
    entity.setSourceAvailable(true);
    entity.setCreatedAt(LocalDateTime.now());
    MapperUtils.save(snapshotMapper, entity);

    packet.evidenceRefs().forEach(ref -> {
      EvidenceSnapshotRefEntity refEntity = new EvidenceSnapshotRefEntity();
      refEntity.setUserId(userId);
      refEntity.setSnapshotId(snapshotId);
      refEntity.setDataDomain(ref.dataDomain());
      refEntity.setResourceId(ref.resourceId());
      refEntity.setResourceVersion(ref.resourceVersion());
      refEntity.setEvidenceId(ref.evidenceId());
      MapperUtils.save(refMapper, refEntity);
    });
    return snapshotId;
  }

  public EvidenceSnapshotEntity get(Long userId, String snapshotId) {
    return snapshotMapper.selectOne(Wrappers.<EvidenceSnapshotEntity>lambdaQuery()
        .eq(EvidenceSnapshotEntity::getUserId, userId)
        .eq(EvidenceSnapshotEntity::getSnapshotId, snapshotId));
  }

  @Transactional(rollbackFor = Exception.class)
  public int markSourceUnavailable(
      Long userId,
      DataDomain dataDomain,
      String resourceId
  ) {
    return markSourceUnavailable(userId, dataDomain, resourceId, null);
  }

  @Transactional(rollbackFor = Exception.class)
  public int markSourceUnavailable(
      Long userId,
      DataDomain dataDomain,
      String resourceId,
      String resourceVersion
  ) {
    var wrapper = Wrappers.<EvidenceSnapshotRefEntity>lambdaQuery()
        .eq(EvidenceSnapshotRefEntity::getUserId, userId)
        .eq(EvidenceSnapshotRefEntity::getDataDomain, dataDomain)
        .eq(EvidenceSnapshotRefEntity::getResourceId, resourceId);
    if (resourceVersion != null && !resourceVersion.isBlank()) {
      wrapper.eq(EvidenceSnapshotRefEntity::getResourceVersion, resourceVersion);
    }
    List<String> snapshotIds = refMapper.selectList(wrapper)
        .stream()
        .map(EvidenceSnapshotRefEntity::getSnapshotId)
        .distinct()
        .toList();
    if (snapshotIds.isEmpty()) {
      return 0;
    }
    List<EvidenceSnapshotEntity> snapshots = snapshotMapper.selectList(
        Wrappers.<EvidenceSnapshotEntity>lambdaQuery()
            .eq(EvidenceSnapshotEntity::getUserId, userId)
            .in(EvidenceSnapshotEntity::getSnapshotId, snapshotIds)
            // 同一快照可能同时引用 JD、简历、GitHub 等多个来源。删除并发发生时必须串行
            // 脱敏，避免后提交事务用旧 packetJson 覆盖并重新留下另一已删除来源的正文。
            .last("FOR UPDATE"));
    int updated = 0;
    for (EvidenceSnapshotEntity snapshot : snapshots) {
      snapshot.setPacketJson(redactPacket(
          snapshot, dataDomain, resourceId, resourceVersion));
      // queryText 是 packetJson 之外的冗余列，准备查询可能包含 JD 原文证据；来源删除后
      // 必须同步清理，不能只脱敏 JSON 正文。
      snapshot.setQueryText("源资料已删除，无法复核");
      snapshot.setSourceAvailable(false);
      updated += snapshotMapper.updateById(snapshot);
    }
    return updated;
  }

  private String redactPacket(
      EvidenceSnapshotEntity snapshot,
      DataDomain dataDomain,
      String resourceId,
      String resourceVersion
  ) {
    try {
      EvidencePacket packet = objectMapper.readValue(snapshot.getPacketJson(), EvidencePacket.class);
      List<EvidenceCandidate> redactedCandidates = packet.candidates().stream()
          .map(candidate -> matches(
              candidate.ref(), dataDomain, resourceId, resourceVersion)
                  ? new EvidenceCandidate(
                      redactRef(candidate.ref()), "", candidate.retrievalScore(),
                      candidate.rerankScore(), candidate.domainWeight(), candidate.rank())
                  : candidate)
          .toList();
      List<String> conflicts = packet.status() == EvidenceStatus.CONFLICT
          ? List.of("源资料已删除，原冲突正文无法复核") : List.of();
      EvidencePacket redacted = new EvidencePacket(
          packet.capabilityAtomKey(), "源资料已删除，无法复核", packet.status(),
          redactedCandidates, conflicts,
          appendReason(packet.degradedReasons(), "SOURCE_DELETED_UNVERIFIABLE"));
      return objectMapper.writeValueAsString(redacted);
    } catch (Exception e) {
      EvidenceStatus status = snapshot.getEvidenceStatus() == null
          ? EvidenceStatus.NONE : snapshot.getEvidenceStatus();
      try {
        return objectMapper.writeValueAsString(new EvidencePacket(
            snapshot.getCapabilityAtomKey(), "源资料已删除，无法复核", status,
            List.of(), status == EvidenceStatus.CONFLICT
                ? List.of("源资料已删除，原冲突正文无法复核") : List.of(),
            List.of("SOURCE_DELETED_UNVERIFIABLE")));
      } catch (JsonProcessingException impossible) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "证据快照脱敏失败", impossible);
      }
    }
  }

  private EvidenceRef redactRef(EvidenceRef ref) {
    return new EvidenceRef(
        ref.evidenceId(), ref.dataDomain(), ref.resourceId(), ref.resourceVersion(),
        ref.sourceType(), "unavailable://deleted", ref.contentHash());
  }

  private boolean matches(
      EvidenceRef ref,
      DataDomain dataDomain,
      String resourceId,
      String resourceVersion
  ) {
    return ref.dataDomain() == dataDomain
        && ref.resourceId().equals(resourceId)
        && (resourceVersion == null || resourceVersion.isBlank()
            || ref.resourceVersion().equals(resourceVersion));
  }

  private List<String> appendReason(List<String> reasons, String reason) {
    java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
    if (reasons != null) {
      result.addAll(reasons);
    }
    result.add(reason);
    return List.copyOf(result);
  }

  private String writeJson(EvidencePacket packet) {
    try {
      return objectMapper.writeValueAsString(packet);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "证据快照序列化失败", e);
    }
  }
}
