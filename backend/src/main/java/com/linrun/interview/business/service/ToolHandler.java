package com.linrun.interview.business.service;

import java.util.Map;

@FunctionalInterface
public interface ToolHandler {
  ToolResult<?> execute(ToolExecutionContext context, Map<String, Object> input);
}
