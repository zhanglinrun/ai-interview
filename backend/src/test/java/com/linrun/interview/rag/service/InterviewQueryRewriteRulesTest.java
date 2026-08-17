package com.linrun.interview.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("查询改写规则测试")
class InterviewQueryRewriteRulesTest {

  @Test
  @DisplayName("应纠正常见技术术语拼写")
  void fixesCommonTypos() {
    assertThat(InterviewQueryRewriteRules.applyRules("sping boot 怎么学"))
        .contains("Spring Boot");
  }

  @Test
  @DisplayName("应展开缩写并口语化映射")
  void expandsAbbreviations() {
    assertThat(InterviewQueryRewriteRules.applyRules("jvm gc 原理"))
        .contains("JVM 垃圾回收");
    assertThat(InterviewQueryRewriteRules.applyRules("redis 挂了怎么办"))
        .contains("Redis 故障排查与高可用");
  }

  @Test
  @DisplayName("应把缓存穿透/击穿/雪崩拆成可区分的检索词")
  void disambiguatesCacheFailureModes() {
    String penetration = InterviewQueryRewriteRules.applyRules("Redis 缓存穿透怎么解决？");
    assertThat(penetration).contains("不存在的数据").contains("缓存和数据库都没有");
    assertThat(penetration).doesNotContain("布隆过滤器");
    assertThat(penetration).doesNotContain("热点 key");

    String breakdown = InterviewQueryRewriteRules.applyRules("什么是缓存击穿");
    assertThat(breakdown).contains("热点 key").contains("互斥锁");
    assertThat(breakdown).doesNotContain("不存在的数据");

    String avalanche = InterviewQueryRewriteRules.applyRules("缓存雪崩怎么防");
    assertThat(avalanche).contains("同时过期").contains("过期时间打散");
  }

  @Test
  @DisplayName("应纠正评测集里的错别字和漏召回术语")
  void fixesEvalSetTerms() {
    assertThat(InterviewQueryRewriteRules.applyRules("重栽和重写有啥区别啊"))
        .contains("重载");
    assertThat(InterviewQueryRewriteRules.applyRules("ArayList 跟 LinkedList 到底差在哪"))
        .contains("ArrayList");
    assertThat(InterviewQueryRewriteRules.applyRules("RocketMQ 事务消息大致怎么走？"))
        .contains("半消息")
        .contains("回查");
    assertThat(InterviewQueryRewriteRules.applyRules("子类重写父类方法时，异常声明要注意什么？"))
        .contains("方法重写");
  }
}
