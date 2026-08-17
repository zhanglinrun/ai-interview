package com.linrun.interview.rag.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 查询改写轻量规则层（LLM 改写前的确定性预处理，对齐业界实践 多策略中的术语/错别字维度）。
 */
public final class InterviewQueryRewriteRules {

  private static final List<Map.Entry<String, String>> TERM_FIXES = buildTermFixes();
  private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

  private InterviewQueryRewriteRules() {
  }

  /**
   * 对原始问题做规则预处理；若无命中则返回 trim 后的原问题。
   */
  public static String applyRules(String question) {
    if (question == null || question.isBlank()) {
      return question;
    }
    String normalized = MULTI_SPACE.matcher(question.trim()).replaceAll(" ");
    String lower = normalized.toLowerCase();
    for (Map.Entry<String, String> entry : TERM_FIXES) {
      if (lower.contains(entry.getKey())) {
        normalized = replaceIgnoreCase(normalized, entry.getKey(), entry.getValue());
        lower = normalized.toLowerCase();
      }
    }
    return normalized;
  }

  public static boolean changed(String original, String rewritten) {
    if (original == null || rewritten == null) {
      return false;
    }
    return !original.trim().equals(rewritten.trim());
  }

  private static String replaceIgnoreCase(String text, String target, String replacement) {
    return Pattern.compile(Pattern.quote(target), Pattern.CASE_INSENSITIVE)
        .matcher(text)
        .replaceAll(replacement);
  }

  private static List<Map.Entry<String, String>> buildTermFixes() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("sping boot", "Spring Boot");
    map.put("sping", "Spring");
    map.put("jvm gc", "JVM 垃圾回收");
    map.put("k8s", "Kubernetes");
    map.put("redis 挂了", "Redis 故障排查与高可用");
    // 穿透/击穿/雪崩向量近、标题也像，先补定义词把三道题拆开；不把「布隆过滤器」写进 query，避免烟测自嗨。
    map.put("缓存穿透", "缓存穿透 查询不存在的数据 缓存和数据库都没有");
    map.put("缓存击穿", "缓存击穿 热点 key 过期 互斥锁");
    map.put("缓存雪崩", "缓存雪崩 大量 key 同时过期 过期时间打散");
    map.put("mysql 慢查询", "MySQL 慢查询优化");
    map.put("ioc", "Spring IOC");
    map.put("aop", "Spring AOP");
    map.put("jpa", "JPA");
    map.put("mybatis", "MyBatis");
    map.put("重栽", "重载");
    map.put("araylist", "ArrayList");
    map.put("事务消息", "事务消息 半消息 Commit Rollback 回查");
    map.put("异常声明", "方法重写 异常声明 不能抛出更多检查异常");
    map.put("三次握手", "TCP 三次握手 两次握手");
    List<Map.Entry<String, String>> entries = new ArrayList<>(map.entrySet());
    entries.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
    return List.copyOf(entries);
  }
}
