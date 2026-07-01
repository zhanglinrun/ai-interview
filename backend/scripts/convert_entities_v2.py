#!/usr/bin/env python3
"""Line-by-line JPA -> MyBatis-Plus entity converter."""
import os
import re

ROOT = r"e:\javaproject\ai-interview\backend\src\main\java\com\linrun\interview"

CONFIG = {
    "modules/user/model/UserEntity.java": ("users", {}),
    "modules/resume/model/ResumeEntity.java": ("resumes", {}),
    "modules/resume/model/ResumeAnalysisEntity.java": ("resume_analyses", {"resume": "resumeId"}),
    "modules/knowledgebase/model/KnowledgeBaseEntity.java": ("knowledge_bases", {}),
    "modules/knowledgebase/model/KnowledgeBaseVersionEntity.java": ("knowledge_base_version", {"pk": "versionId"}),
    "modules/knowledgebase/model/KnowledgeBaseSegmentEntity.java": ("knowledge_base_segment", {}),
    "modules/knowledgebase/model/KnowledgeBaseDataTableEntity.java": ("knowledge_base_data_tables", {}),
    "modules/knowledgebase/model/RagChatSessionEntity.java": ("rag_chat_sessions", {"transient": ["knowledgeBases", "messages"]}),
    "modules/knowledgebase/model/RagChatMessageEntity.java": ("rag_chat_messages", {"session": "sessionId"}),
    "modules/knowledgebase/model/RagEvaluationRunEntity.java": ("rag_evaluation_runs", {}),
    "modules/knowledgebase/model/RagQueryTraceEntity.java": ("rag_query_traces", {}),
    "modules/interview/model/InterviewSessionEntity.java": ("interview_sessions", {"transient": ["resume", "answers"]}),
    "modules/interview/model/InterviewAnswerEntity.java": ("interview_answers", {"session": "sessionId", "transient_session": True}),
    "modules/interviewschedule/model/InterviewScheduleEntity.java": ("interview_schedule", {}),
    "modules/voiceinterview/model/VoiceInterviewSessionEntity.java": ("voice_interview_sessions", {}),
    "modules/voiceinterview/model/VoiceInterviewMessageEntity.java": ("voice_interview_messages", {}),
    "modules/voiceinterview/model/VoiceInterviewEvaluationEntity.java": ("voice_interview_evaluations", {}),
    "modules/llmprovider/model/LlmProviderEntity.java": ("llm_provider_config", {"pk_input": True}),
    "modules/llmprovider/model/LlmGlobalSettingEntity.java": ("llm_global_setting", {}),
}

SKIP_LINES_STARTS = (
    "@Entity", "@Table", "@Index", "@UniqueConstraint",
    "@GeneratedValue", "@Id", "@Column", "@Enumerated",
    "@ManyToOne", "@OneToMany", "@ManyToMany", "@JoinColumn",
    "@JoinTable", "@FetchType", "@OrderBy", "@PrePersist",
    "@PreUpdate", "@PostLoad", "import jakarta.persistence",
)
SKIP_BLOCK_STARTS = ("@JoinTable(",)


def should_skip_line(line: str) -> bool:
    s = line.strip()
    if not s:
        return False
    if s.startswith(SKIP_LINES_STARTS):
        return True
    if s in ("{", "})") or s.startswith("@Index(") or s.startswith("@UniqueConstraint("):
        return True
    return False


def in_lifecycle_method(lines, i):
    if "@PrePersist" in lines[i] or "@PreUpdate" in lines[i] or "@PostLoad" in lines[i]:
        return True
    return False


def skip_lifecycle(lines, i):
    while i < len(lines) and not (lines[i].strip().startswith("protected void") or lines[i].strip().startswith("void pre")):
        i += 1
    if i >= len(lines):
        return i
    depth = 0
    while i < len(lines):
        depth += lines[i].count("{") - lines[i].count("}")
        i += 1
        if depth <= 0 and i > 0 and "}" in lines[i - 1]:
            break
    return i


def skip_join_table(lines, i):
    while i < len(lines) and not lines[i].strip().endswith(");"):
        i += 1
    return i + 1


def convert(path_rel, table, opts):
    path = os.path.join(ROOT, path_rel.replace("/", os.sep))
    with open(path, encoding="utf-8") as f:
        lines = f.readlines()

    out = []
    i = 0
    added_table = False
    added_mp_import = False
    pk_name = opts.get("pk", "id")
    rel_map = {k: v for k, v in opts.items() if k not in ("pk", "pk_input", "transient", "transient_session")}

    while i < len(lines):
        line = lines[i]
        if "import jakarta.persistence" in line:
            i += 1
            continue
        if line.strip().startswith("@Entity") or line.strip().startswith("@Table"):
            i += 1
            while i < len(lines) and lines[i].strip() not in ("public class",) and not lines[i].strip().startswith("public class "):
                if lines[i].strip() in ("{", "})", "},") or lines[i].strip().startswith("@Index"):
                    i += 1
                    continue
                if lines[i].strip().startswith("public class"):
                    break
                i += 1
            continue
        if line.strip().startswith("@PrePersist") or line.strip().startswith("@PreUpdate") or line.strip().startswith("@PostLoad"):
            i = skip_lifecycle(lines, i)
            continue
        if line.strip().startswith("@JoinTable"):
            i = skip_join_table(lines, i)
            continue
        if should_skip_line(line):
            i += 1
            continue

        # package -> add imports
        if line.startswith("package ") and not added_mp_import:
            out.append(line)
            out.append("\n")
            out.append("import com.baomidou.mybatisplus.annotation.IdType;\n")
            out.append("import com.baomidou.mybatisplus.annotation.TableField;\n")
            out.append("import com.baomidou.mybatisplus.annotation.TableId;\n")
            out.append("import com.baomidou.mybatisplus.annotation.TableName;\n")
            added_mp_import = True
            i += 1
            continue

        # before public class add @TableName
        if not added_table and line.strip().startswith("public class "):
            out.append(f'@TableName("{table}")\n')
            added_table = True

        # replace relationship fields
        stripped = line.strip()
        replaced = False
        for rel_field, fk in rel_map.items():
            if f"private {rel_field[0].upper()}" in line or f"private {rel_field}" in line:
                # match entity type fields
                m = re.match(r"(\s*)private\s+\w+Entity\s+" + rel_field + r"\s*;", stripped)
                if m or (rel_field == "resume" and "private ResumeEntity resume" in stripped):
                    indent = re.match(r"(\s*)", line).group(1)
                    out.append(f"{indent}private Long {fk};\n")
                    if opts.get("transient_session") and rel_field == "session":
                        out.append(f"{indent}@TableField(exist = false)\n")
                        out.append(f"{indent}private InterviewSessionEntity session;\n")
                    replaced = True
                    break
                m2 = re.match(r"(\s*)private\s+RagChatSessionEntity\s+session\s*;", stripped)
                if rel_field == "session" and m2:
                    indent = m2.group(1)
                    out.append(f"{indent}private Long sessionId;\n")
                    replaced = True
                    break
        if replaced:
            i += 1
            continue

        # transient collections
        if opts.get("transient"):
            for tf in opts["transient"]:
                if f"private" in stripped and tf in stripped and ("Set<" in stripped or "List<" in stripped or "ResumeEntity" in stripped):
                    indent = re.match(r"(\s*)", line).group(1)
                    out.append(f"{indent}@TableField(exist = false)\n")
                    out.append(line)
                    i += 1
                    replaced = True
                    break
        if replaced:
            continue

        # add @TableId before id field
        if pk_name == "versionId" and stripped == "private Long versionId;":
            indent = re.match(r"(\s*)", line).group(1)
            out.append(f'{indent}@TableId(value = "version_id", type = IdType.AUTO)\n')
            out.append(line)
            i += 1
            continue
        if opts.get("pk_input") and stripped == "private String id;":
            indent = re.match(r"(\s*)", line).group(1)
            out.append(f"{indent}@TableId(type = IdType.INPUT)\n")
            out.append(line)
            i += 1
            continue
        if stripped == "private Long id;" and pk_name == "id":
            indent = re.match(r"(\s*)", line).group(1)
            out.append(f"{indent}@TableId(type = IdType.AUTO)\n")
            out.append(line)
            i += 1
            continue

        # fix resume_id duplicate
        if 'insertable = false' in stripped:
            i += 1
            continue

        out.append(line)
        i += 1

    content = "".join(out)
    # fix RagChatMessage setSession
    if "RagChatMessageEntity" in content and "void setSession(RagChatSessionEntity" not in content:
        content = content.replace(
            "public String getTypeString()",
            "public void setSession(RagChatSessionEntity session) {\n"
            "        this.sessionId = session != null ? session.getId() : null;\n"
            "    }\n\n"
            "    public Long getSessionId() {\n"
            "        return sessionId;\n"
            "    }\n\n"
            "    public void setSessionId(Long sessionId) {\n"
            "        this.sessionId = sessionId;\n"
            "    }\n\n"
            "    public String getTypeString()",
        )
    if "RagChatSessionEntity" in content:
        content = content.replace("message.setSession(this);", "message.setSessionId(this.id);")

    if "ResumeAnalysisEntity" in content:
        content = content.replace(
            "public void setResume(ResumeEntity resume) {\n        this.resume = resume;\n    }",
            "public Long getResumeId() {\n        return resumeId;\n    }\n\n"
            "    public void setResumeId(Long resumeId) {\n        this.resumeId = resumeId;\n    }\n\n"
            "    public void setResume(ResumeEntity resume) {\n        this.resumeId = resume != null ? resume.getId() : null;\n    }",
        )
        if "private Long resumeId;" in content and "getResumeId" not in content:
            pass

    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print("OK", path_rel)


def main():
    for rel, (table, opts) in CONFIG.items():
        convert(rel, table, opts)


if __name__ == "__main__":
    main()
