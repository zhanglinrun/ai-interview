package com.linrun.interview.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * traceId 贯通过滤器（P5）。
 *
 * <p>读取前端注入的 {@value #HEADER}（无则生成），写入 {@link LangfuseContext} 与 MDC，
 * 使「前端一次操作 → 后端日志 → Langfuse trace」用同一个 id 串起来；并回写响应头，
 * 让前端拿到 traceId 后可在「查看完整链路」跳到 Langfuse trace 详情页。
 *
 * <p>与 Langfuse 开关无关：即使观测关闭，traceId 仍写入 MDC 供日志排查。
 */
public class TraceIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Trace-Id";
  private static final int MAX_LEN = 64;

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {
    String traceId = request.getHeader(HEADER);
    if (traceId == null || traceId.isBlank()) {
      traceId = UUID.randomUUID().toString();
    } else if (traceId.length() > MAX_LEN) {
      traceId = traceId.substring(0, MAX_LEN);
    }
    LangfuseContext.setTraceId(traceId);
    response.setHeader(HEADER, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      LangfuseContext.clear();
    }
  }
}
