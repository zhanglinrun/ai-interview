package com.linrun.interview.common.evidence;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一次证据检索允许访问的完整边界。
 *
 * <p>范围必须显式列出 domain + resourceId；私有域固定绑定 dataUserId，PLATFORM 域固定绑定公共
 * owner 0。这样调用方无法通过“只传一个 domain”意外检索该用户的全部资料。
 */
public record EvidenceScope(
    Long dataUserId,
    List<DomainScope> domains,
    boolean includePersonalMaterials
) {

  public EvidenceScope {
    if (dataUserId == null || dataUserId <= 0) {
      throw new IllegalArgumentException("dataUserId 必须为有效用户 ID");
    }
    if (domains == null || domains.isEmpty()) {
      throw new IllegalArgumentException("至少需要一个证据域");
    }
    domains = normalizeDomains(domains, includePersonalMaterials);
  }

  public static EvidenceScope candidateKnowledgeBases(Long dataUserId, Collection<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      throw new IllegalArgumentException("knowledgeBaseIds 不能为空");
    }
    Set<String> resourceIds = ids.stream()
        .filter(Objects::nonNull)
        .map(String::valueOf)
        .collect(Collectors.toUnmodifiableSet());
    return new EvidenceScope(
        dataUserId,
        List.of(new DomainScope(DataDomain.CANDIDATE, resourceIds, Set.of(), 1.0d)),
        true);
  }

  public EvidenceScope only(DataDomain domain) {
    List<DomainScope> selected = domains.stream()
        .filter(item -> item.domain() == domain)
        .toList();
    if (selected.isEmpty()) {
      throw new IllegalArgumentException("证据范围未包含域: " + domain);
    }
    return new EvidenceScope(dataUserId, selected, includePersonalMaterials);
  }

  public long ownerFor(DataDomain domain) {
    return domain == DataDomain.PLATFORM ? DataDomain.PLATFORM_OWNER_USER_ID : dataUserId;
  }

  public boolean contains(DataDomain domain, String resourceId, String resourceVersion, Long ownerUserId) {
    if (domain == null || ownerUserId == null || resourceId == null) {
      return false;
    }
    if (ownerFor(domain) != ownerUserId) {
      return false;
    }
    return domains.stream().anyMatch(item -> item.matches(domain, resourceId, resourceVersion));
  }

  public Set<DataDomain> dataDomains() {
    EnumSet<DataDomain> result = EnumSet.noneOf(DataDomain.class);
    domains.forEach(item -> result.add(item.domain()));
    return Set.copyOf(result);
  }

  private static List<DomainScope> normalizeDomains(
      List<DomainScope> domains,
      boolean includePersonalMaterials
  ) {
    Map<DataDomain, DomainScope> unique = new LinkedHashMap<>();
    for (DomainScope domain : domains) {
      Objects.requireNonNull(domain, "domainScope");
      if (!includePersonalMaterials && domain.domain() == DataDomain.CANDIDATE) {
        throw new IllegalArgumentException("关闭个人资料时不能包含 CANDIDATE 域");
      }
      if (unique.putIfAbsent(domain.domain(), domain) != null) {
        throw new IllegalArgumentException("同一证据域只能配置一次: " + domain.domain());
      }
    }
    return List.copyOf(unique.values());
  }

  /** 单个数据域的资源、版本和召回权重。 */
  public record DomainScope(
      DataDomain domain,
      Set<String> resourceIds,
      Set<String> resourceVersions,
      double weight
  ) {

    public DomainScope {
      domain = Objects.requireNonNull(domain, "domain");
      resourceIds = normalizeRequired(resourceIds, "resourceIds");
      resourceVersions = normalizeOptional(resourceVersions);
      if (!Double.isFinite(weight) || weight <= 0.0d || weight > 10.0d) {
        throw new IllegalArgumentException("weight 必须在 (0, 10] 范围内");
      }
    }

    public boolean matches(DataDomain actualDomain, String resourceId, String resourceVersion) {
      if (domain != actualDomain || !resourceIds.contains(resourceId)) {
        return false;
      }
      return resourceVersions.isEmpty() || resourceVersions.contains(resourceVersion);
    }

    private static Set<String> normalizeRequired(Set<String> values, String field) {
      Set<String> normalized = normalizeOptional(values);
      if (normalized.isEmpty()) {
        throw new IllegalArgumentException(field + " 不能为空");
      }
      return normalized;
    }

    private static Set<String> normalizeOptional(Set<String> values) {
      if (values == null || values.isEmpty()) {
        return Set.of();
      }
      return values.stream()
          .filter(Objects::nonNull)
          .map(String::trim)
          .filter(value -> !value.isBlank())
          .collect(Collectors.toUnmodifiableSet());
    }
  }
}
