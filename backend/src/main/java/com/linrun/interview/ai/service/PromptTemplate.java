package com.linrun.interview.ai.service;

import java.util.Map;
import java.util.Objects;

/**
 * 轻量级 prompt 模板，替代 Spring AI 的 {@code org.springframework.ai.chat.prompt.PromptTemplate}。
 *
 * <p>占位符语法：{@code {varName}}（单花括号），与资源目录中的纯文本 Prompt 文件兼容。
 * 渲染时只替换 {@code vars} 中存在的 key，未知的 {@code {xxx}} 原样保留——这样模板里的
 * JSON 示例花括号不会被误伤，且比 Spring AI 的"未知变量报错"行为更安全。
 *
 * <p>仅依赖 JDK，不引入 StringTemplate / Commons Text 等第三方库。
 */
public final class PromptTemplate {

    private final String template;

    public PromptTemplate(String template) {
        this.template = template == null ? "" : template;
    }

    /**
     * 无变量渲染，直接返回模板原文。
     */
    public String render() {
        return template;
    }

    /**
     * 用给定变量渲染模板，只替换 vars 中存在的 {@code {key}}，未知占位符原样保留。
     *
     * @param variables 变量映射；key 对应模板里的 {@code {key}}，value 调用 toString
     * @return 渲染后的字符串
     */
    public String render(Map<String, Object> variables) {
        if (variables == null || variables.isEmpty() || template.isEmpty()) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isEmpty()) {
                continue;
            }
            String placeholder = "{" + key + "}";
            String value = entry.getValue() == null ? "" : Objects.toString(entry.getValue());
            result = result.replace(placeholder, value);
        }
        return result;
    }
}
