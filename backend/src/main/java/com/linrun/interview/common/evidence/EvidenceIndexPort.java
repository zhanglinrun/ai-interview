package com.linrun.interview.common.evidence;

import java.util.List;

/** PLATFORM/JOB/CANDIDATE/GITHUB 共享的统一索引写入与清理端口。 */
public interface EvidenceIndexPort {

  List<String> index(List<EvidenceIndexChunk> chunks);

  void delete(
      Long ownerUserId,
      DataDomain dataDomain,
      String resourceId,
      String resourceVersion
  );
}
