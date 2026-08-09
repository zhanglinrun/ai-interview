package com.linrun.interview.rag.model;

/**
 * 可检索资料的业务边界。域是权限与召回范围的一部分，不能只作为展示标签。
 */
public enum DataDomain {
  PLATFORM,
  JOB,
  CANDIDATE,
  GITHUB;

  /** PLATFORM 资料使用明确公共 owner，禁止使用 null 表示公共数据。 */
  public static final long PLATFORM_OWNER_USER_ID = 0L;

  public boolean isPrivateDomain() {
    return this != PLATFORM;
  }
}
