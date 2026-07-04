package com.linrun.interview.common.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Langfuse 可观测配置（P5）。
 *
 * <p>Java 侧无官方 Langfuse SDK，本项目自研 {@link LangfuseTracer} 直连 Langfuse
 * REST ingestion API（batch 异步上报 + 失败静默降级）。默认关闭，配置齐全后开启。
 *
 * <pre>
 * app:
 *   observability:
 *     langfuse:
 *       enabled: true
 *       base-url: http://localhost:3000
 *       public-key: pk-lf-xxx
 *       secret-key: sk-lf-xxx
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "app.observability.langfuse")
public class LangfuseProperties {

  /** 总开关；关闭时 tracer 全链路 no-op、监听器不注册，零业务影响。 */
  private boolean enabled = false;

  /** Langfuse 服务地址（自建默认 3000）。 */
  private String baseUrl = "http://localhost:3000";

  private String publicKey;
  private String secretKey;

  /** 环境标（dev/staging/prod），写入 trace 便于区分。 */
  private String environment = "dev";

  /** 异步 flush 间隔（毫秒）。 */
  private long flushIntervalMs = 2000;

  /** 单批最大事件数。 */
  private int batchSize = 20;

  /** 队列上限，超出丢弃最旧事件（上报是旁路，绝不背压业务）。 */
  private int maxQueue = 2000;

  /** 单个 input/output 字段最大字符数，超出截断防止 payload 过大。 */
  private int maxFieldChars = 4000;

  /** trace 详情页 URL 模板，支持 {baseUrl} / {traceId} 占位（前端跳转用）。 */
  private String traceUrlTemplate = "{baseUrl}/trace/{traceId}";
}
