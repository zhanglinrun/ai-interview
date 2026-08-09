package com.linrun.interview.infra.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * traceId 贯通过滤器（P5）。
 *
 * <p>读取前端注入的 {@value #HEADER}（无则生成），写入 {@link TraceContext} 与 MDC，
 * 并回写响应头，使一次前端操作与后端日志可通过同一个 traceId 关联。
 */
public class TraceIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Trace-Id";
  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {
    String traceId = request.getHeader(HEADER);
    traceId = TraceIdPolicy.acceptOrCreate(traceId);
    TraceContext.setTraceId(traceId);
    response.setHeader(HEADER, traceId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      TraceContext.clear();
    }
  }
}
