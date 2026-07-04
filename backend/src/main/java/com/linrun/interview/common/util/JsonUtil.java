package com.linrun.interview.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 脏 JSON 容错解析工具（参考业界实现 {@code infra/json/JsonUtil}，取精华弃糟粕）。
 *
 * <p>意图识别、查询路由等解析 LLM 返回 JSON 时前置容错：剥 markdown 代码块、裁前后垃圾字符、
 * 修中文引号 / 单引号 / 尾逗号 / 无引号键名，最后 jackson 验证；仍失败则兜底包成
 * {@code {"content":"..."}}。
 *
 * <p><b>弃糟粕</b>：删掉 业界实现 的 {@code fixEscapeChars}（用正则把字符串内裸
 * {@code \n}/>{@code \r}/>{@code \t} 替换成空格，会破坏 JSON 字符串里合法的换行内容；
 * jackson 本身能正确处理转义，裸换行在标准 JSON 字符串里虽非法但 jackson 多数能容忍，
 * 该步收益低、风险高，直接去掉）。
 */
public final class JsonUtil {

    private static final Logger log = LoggerFactory.getLogger(JsonUtil.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Pattern MARKDOWN_JSON_PATTERN =
        Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNQUOTED_KEY_PATTERN =
        Pattern.compile("([{,]\\s*)([a-zA-Z_][a-zA-Z0-9_]*)\\s*:");

    private JsonUtil() {
    }

    /**
     * 修复可能含错的 JSON 字符串（5 步修复，弃 fixEscapeChars）。
     *
     * @param jsonString 可能含错的 JSON 字符串
     * @return 修复后的 JSON 字符串；输入空返回 {@code "{}"}
     */
    public static String fixJson(String jsonString) {
        if (jsonString == null || jsonString.trim().isEmpty()) {
            return "{}";
        }

        String fixed = jsonString.trim();
        fixed = extractJsonFromMarkdown(fixed);
        fixed = removeLeadingTrailingGarbage(fixed);
        fixed = fixQuotes(fixed);
        fixed = fixTrailingCommas(fixed);
        fixed = fixMissingQuotes(fixed);

        try {
            OBJECT_MAPPER.readTree(fixed);
            return fixed;
        } catch (Exception e) {
            log.warn("JSON 修复后仍然无效，兜底包装为简单对象。Error: {}", e.getMessage());
            return wrapAsSimpleJson(jsonString);
        }
    }

    /**
     * 修复并解析为 {@link JsonNode}；解析失败返回空对象节点，不抛异常。
     *
     * @param jsonString JSON 字符串
     * @return JsonNode 对象（永不为 null）
     */
    public static JsonNode fixAndParse(String jsonString) {
        String fixed = fixJson(jsonString);
        try {
            return OBJECT_MAPPER.readTree(fixed);
        } catch (Exception e) {
            log.error("JSON 解析失败: {}", e.getMessage(), e);
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    /**
     * 验证 JSON 字符串是否有效。
     *
     * @param jsonString JSON 字符串
     * @return true 有效；false 无效
     */
    public static boolean isValidJson(String jsonString) {
        if (jsonString == null || jsonString.isBlank()) {
            return false;
        }
        try {
            OBJECT_MAPPER.readTree(jsonString);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 从 {@code ```json ... ```} 或 {@code ``` ... ```} 代码块中提取 JSON。 */
    private static String extractJsonFromMarkdown(String text) {
        Matcher matcher = MARKDOWN_JSON_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return text;
    }

    /** 裁掉首个 {@code {}/>{@code [} 之前与末个 {@code {}/>{@code ]} 之后的垃圾字符。 */
    private static String removeLeadingTrailingGarbage(String text) {
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                break;
            }
        }
        int end = -1;
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '}' || c == ']') {
                end = i + 1;
                break;
            }
        }
        if (start != -1 && end != -1 && start < end) {
            return text.substring(start, end);
        }
        return text;
    }

    /** 修引号：中文双引号→英文双引号，中文单引号→英文单引号，单引号字符串→双引号字符串。 */
    private static String fixQuotes(String text) {
        text = text.replace("\u201c", "\"").replace("\u201d", "\"");
        text = text.replace("\u2018", "'").replace("\u2019", "'");
        text = text.replaceAll("'([^']*?)'", "\"$1\"");
        return text;
    }

    /** 去对象 {@code ,}} 与数组 {@code ,]} 尾逗号。 */
    private static String fixTrailingCommas(String text) {
        text = text.replaceAll(",\\s*}", "}");
        text = text.replaceAll(",\\s*]", "]");
        return text;
    }

    /** 为无引号的键名加双引号：{@code {word:} / {@code ,word:} → {@code "word":}。 */
    private static String fixMissingQuotes(String text) {
        return UNQUOTED_KEY_PATTERN.matcher(text).replaceAll("$1\"$2\":");
    }

    /** 兜底：把任意文本转义后包成 {@code {"content":"..."}}；转义失败返回 {@code {"error":"Invalid JSON"}}。 */
    private static String wrapAsSimpleJson(String text) {
        try {
            String escaped = text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
            return "{\"content\":\"" + escaped + "\"}";
        } catch (Exception e) {
            return "{\"error\":\"Invalid JSON\"}";
        }
    }
}
