package com.linrun.interview.modules.interview.service;

import com.linrun.interview.common.ai.PromptSanitizer;
import com.linrun.interview.modules.interview.skill.InterviewSkillService.SkillCategoryDTO;
import com.linrun.interview.modules.interview.skill.InterviewSkillService.SkillDTO;
import com.linrun.interview.modules.knowledgebase.constant.MetadataKeyConstant;
import com.linrun.interview.modules.knowledgebase.service.KnowledgeBaseQueryService;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 面试出题的知识库检索桥接：会话关联知识库时，按 Skill 主题词走 RAG 检索链
 * （改写/路由/混合检索/rerank 与前端 RAG 查询共用），把命中的 chunk 组装成
 * 出题 prompt 的「岗位知识库参考」段落。
 *
 * <p>检索必须在请求线程内完成（{@code UserContext} 为 ThreadLocal，
 * 不能进出题的虚拟线程池），因此由 {@link InterviewQuestionService} 在派发并行出题前调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewKnowledgeRetrievalService {

    /** 注入出题 prompt 的最大 chunk 数 */
    private static final int MAX_CHUNKS = 6;
    /** 单个 chunk 注入的最大字符数 */
    private static final int MAX_CHARS_PER_CHUNK = 600;
    /** 拼接查询时最多使用的分类数 */
    private static final int MAX_QUERY_CATEGORIES = 4;

    private final KnowledgeBaseQueryService knowledgeBaseQueryService;
    private final PromptSanitizer promptSanitizer;

    /**
     * 按 Skill 主题词检索关联知识库，返回可直接拼进出题 prompt 的参考段落。
     * 知识库为空或检索无命中时返回空字符串（出题降级为无知识库路径，不阻断主流程）。
     */
    public String buildKbReferenceSection(List<Long> knowledgeBaseIds, SkillDTO skill) {
        if (knowledgeBaseIds == null || knowledgeBaseIds.isEmpty() || skill == null) {
            return "";
        }
        String query = buildQuery(skill);
        try {
            List<TextSegment> segments =
                knowledgeBaseQueryService.retrieveForEvaluation(knowledgeBaseIds, query);
            if (segments.isEmpty()) {
                log.info("面试出题知识库检索无命中: kbIds={}, query={}", knowledgeBaseIds, query);
                return "";
            }
            List<TextSegment> top = segments.stream().limit(MAX_CHUNKS).toList();
            log.info("面试出题知识库检索命中: kbIds={}, query={}, hit={}, sources={}",
                knowledgeBaseIds, query, top.size(),
                top.stream().map(this::describeSource).distinct().toList());

            String body = top.stream()
                .map(segment -> "- [" + describeSource(segment) + "] " + truncate(segment.text()))
                .collect(Collectors.joining("\n"));
            return "以下是岗位关联知识库中检索到的资料要点，出题时请优先围绕这些资料的知识点，"
                + "并保证题目仍符合面试方向与难度要求：\n"
                + promptSanitizer.wrapWithDelimiters("kb_reference", promptSanitizer.sanitize(body));
        } catch (Exception e) {
            log.warn("面试出题知识库检索失败，降级为无知识库出题: kbIds={}", knowledgeBaseIds, e);
            return "";
        }
    }

    private String buildQuery(SkillDTO skill) {
        String categories = skill.categories() == null ? "" : skill.categories().stream()
            .limit(MAX_QUERY_CATEGORIES)
            .map(SkillCategoryDTO::label)
            .collect(Collectors.joining(" "));
        return (skill.name() + " " + categories + " 核心知识点 面试考点").trim();
    }

    private String describeSource(TextSegment segment) {
        if (segment.metadata() == null) {
            return "知识库";
        }
        String fileName = segment.metadata().getString(MetadataKeyConstant.FILE_NAME);
        if (fileName != null && !fileName.isBlank()) {
            return fileName;
        }
        String docId = segment.metadata().getString(MetadataKeyConstant.DOC_ID);
        return docId != null ? "doc-" + docId : "知识库";
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.strip();
        return normalized.length() <= MAX_CHARS_PER_CHUNK
            ? normalized
            : normalized.substring(0, MAX_CHARS_PER_CHUNK) + "…";
    }
}
