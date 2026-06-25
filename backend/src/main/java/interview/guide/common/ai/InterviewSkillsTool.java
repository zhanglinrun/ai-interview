package interview.guide.common.ai;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 面试 Skills 加载工具（LangChain4j @Tool）。
 *
 * <p>替代原 spring-ai-agent-utils 的 SkillsTool：让 LLM 在出题时按需加载
 * {@code resources/skills/{skillId}/SKILL.md} 的 persona（角色设定 body）。
 * 与 {@code InterviewSkillService} 互补——后者在 Java 层解析 skill.meta.yml 与
 * references 注入 Prompt，本工具让 LLM 自主拉取 persona 文本。
 *
 * <p>通过 {@code AiServices.builder().tools(skillsTool)} 挂载，仅在 AiServices 场景生效。
 */
@Component
@Slf4j
public class InterviewSkillsTool {

    private static final Pattern FRONT_MATTER_PATTERN =
        Pattern.compile("(?s)^---\\s*\\n.*?\\n---\\s*\\n?(.*)$");

    private final ResourceLoader resourceLoader;
    private final AgentUtilsProperties agentUtilsProperties;

    public InterviewSkillsTool(ResourceLoader resourceLoader, AgentUtilsProperties agentUtilsProperties) {
        this.resourceLoader = resourceLoader;
        this.agentUtilsProperties = agentUtilsProperties;
    }

    /**
     * 加载指定面试方向的 persona（SKILL.md body）。
     * 供 LLM 在需要了解某方向面试官角色设定时调用。
     *
     * @param skillId 面试方向标识，如 java-backend、frontend、algorithm
     * @return SKILL.md 的 body 内容（已去除 front matter）；不存在时返回提示信息
     */
    @Tool("加载指定面试方向的面试官角色设定（persona）。skillId 取值如 java-backend、frontend、algorithm、system-design、ai-agent-dev、test-development 等。返回该方向 SKILL.md 的角色设定正文，用于按该方向风格出题。")
    public String loadSkillPersona(@P("面试方向标识 skillId") String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return "skillId 为空，无法加载 persona。";
        }
        String trimmed = skillId.trim();
        String location = normalizeSkillsRoot(agentUtilsProperties.getSkillsRoot())
            + "/" + trimmed + "/SKILL.md";
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.warn("[InterviewSkillsTool] SKILL.md 不存在: skillId={}, location={}", trimmed, location);
            return "未找到面试方向 " + trimmed + " 的角色设定，请确认 skillId 是否正确。";
        }
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            String body = stripFrontMatter(content);
            log.debug("[InterviewSkillsTool] 加载 persona: skillId={}, chars={}", trimmed, body.length());
            return body;
        } catch (IOException e) {
            log.error("[InterviewSkillsTool] 读取 SKILL.md 失败: skillId={}", trimmed, e);
            return "加载面试方向 " + trimmed + " 的角色设定失败。";
        }
    }

    private String stripFrontMatter(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        if (!content.startsWith("---")) {
            return content.trim();
        }
        var matcher = FRONT_MATTER_PATTERN.matcher(content);
        if (matcher.matches()) {
            return matcher.group(1).trim();
        }
        return content.trim();
    }

    private String normalizeSkillsRoot(String raw) {
        if (raw == null || raw.isBlank()) {
            return "classpath:skills";
        }
        String normalized = raw.trim().replace('\\', '/');
        if (normalized.endsWith("/SKILL.md")) {
            normalized = normalized.substring(0, normalized.length() - "/SKILL.md".length());
        }
        int wildcardIndex = normalized.indexOf('*');
        if (wildcardIndex >= 0) {
            normalized = normalized.substring(0, wildcardIndex);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? "classpath:skills" : normalized;
    }
}
