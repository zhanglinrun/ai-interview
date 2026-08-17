package com.linrun.interview.business.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** agent_steps.metadata_json：节点类型与 chat 计量，避免改表。 */
public final class AgentSpanMetadata {

  public static final String KIND_AGENT = "agent";
  public static final String KIND_CHAT = "chat";
  public static final String KIND_TOOL = "tool";

  private AgentSpanMetadata() {
  }

  public static String write(ObjectMapper mapper, String kind, String model,
                             Integer inputTokens, Integer outputTokens, String operation) {
    ObjectMapper safe = mapper == null ? new ObjectMapper() : mapper;
    ObjectNode node = safe.createObjectNode();
    if (kind != null && !kind.isBlank()) {
      node.put("kind", kind);
    }
    if (model != null && !model.isBlank()) {
      node.put("model", model);
    }
    if (inputTokens != null) {
      node.put("inputTokens", inputTokens);
    }
    if (outputTokens != null) {
      node.put("outputTokens", outputTokens);
    }
    if (operation != null && !operation.isBlank()) {
      node.put("operation", operation);
    }
    try {
      return safe.writeValueAsString(node);
    } catch (Exception e) {
      return "{\"kind\":\"" + (kind == null ? KIND_AGENT : kind) + "\"}";
    }
  }

  public static String kindOf(JsonNode node, String action, String role) {
    if (node != null && node.hasNonNull("kind")) {
      return node.get("kind").asText();
    }
    String safeAction = action == null ? "" : action.toLowerCase();
    if ("chat".equals(safeAction)) {
      return KIND_CHAT;
    }
    if (safeAction.contains(".")
        || "readresume".equals(safeAction)
        || "evidence.search".equals(safeAction)
        || "resume.read".equals(safeAction)) {
      return KIND_TOOL;
    }
    if ("interviewer".equalsIgnoreCase(role)
        && !safeAction.isBlank()
        && !safeAction.equals("ask")
        && !safeAction.equals("ask_failed")) {
      return KIND_TOOL;
    }
    return KIND_AGENT;
  }

  public static String text(JsonNode node, String field) {
    if (node == null || !node.hasNonNull(field)) {
      return null;
    }
    String value = node.get(field).asText();
    return value == null || value.isBlank() ? null : value;
  }

  public static Integer integer(JsonNode node, String field) {
    if (node == null || !node.has(field) || node.get(field).isNull()) {
      return null;
    }
    return node.get(field).isNumber() ? node.get(field).asInt() : null;
  }
}
