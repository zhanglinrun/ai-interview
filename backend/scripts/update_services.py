#!/usr/bin/env python3
"""Bulk replace JPA Repository with MyBatis Mapper in service files."""
import os
import re

ROOT = r"e:\javaproject\ai-interview\backend\src\main\java"

REPO_TO_MAPPER = {
    "UserRepository": ("com.linrun.interview.modules.user.mapper.UserMapper", "userMapper"),
    "ResumeRepository": ("com.linrun.interview.modules.resume.mapper.ResumeMapper", "resumeMapper"),
    "ResumeAnalysisRepository": ("com.linrun.interview.modules.resume.mapper.ResumeAnalysisMapper", "resumeAnalysisMapper"),
    "KnowledgeBaseRepository": ("com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseMapper", "knowledgeBaseMapper"),
    "KnowledgeBaseVersionRepository": ("com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseVersionMapper", "knowledgeBaseVersionMapper"),
    "KnowledgeBaseSegmentRepository": ("com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseSegmentMapper", "knowledgeBaseSegmentMapper"),
    "KnowledgeBaseDataTableRepository": ("com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseDataTableMapper", "knowledgeBaseDataTableMapper"),
    "RagChatSessionRepository": ("com.linrun.interview.modules.knowledgebase.mapper.RagChatSessionMapper", "ragChatSessionMapper"),
    "RagChatMessageRepository": ("com.linrun.interview.modules.knowledgebase.mapper.RagChatMessageMapper", "ragChatMessageMapper"),
    "RagEvaluationRunRepository": ("com.linrun.interview.modules.knowledgebase.mapper.RagEvaluationRunMapper", "ragEvaluationRunMapper"),
    "RagQueryTraceRepository": ("com.linrun.interview.modules.knowledgebase.mapper.RagQueryTraceMapper", "ragQueryTraceMapper"),
    "InterviewSessionRepository": ("com.linrun.interview.modules.interview.mapper.InterviewSessionMapper", "interviewSessionMapper"),
    "InterviewAnswerRepository": ("com.linrun.interview.modules.interview.mapper.InterviewAnswerMapper", "interviewAnswerMapper"),
    "InterviewScheduleRepository": ("com.linrun.interview.modules.interviewschedule.mapper.InterviewScheduleMapper", "interviewScheduleMapper"),
    "VoiceInterviewSessionRepository": ("com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewSessionMapper", "voiceInterviewSessionMapper"),
    "VoiceInterviewMessageRepository": ("com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewMessageMapper", "voiceInterviewMessageMapper"),
    "VoiceInterviewEvaluationRepository": ("com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewEvaluationMapper", "voiceInterviewEvaluationMapper"),
    "LlmProviderRepository": ("com.linrun.interview.modules.llmprovider.mapper.LlmProviderMapper", "llmProviderMapper"),
    "LlmGlobalSettingRepository": ("com.linrun.interview.modules.llmprovider.mapper.LlmGlobalSettingMapper", "llmGlobalSettingMapper"),
}


def process_file(path):
    with open(path, encoding="utf-8") as f:
        content = f.read()
    orig = content

    for repo, (mapper_import, mapper_field) in REPO_TO_MAPPER.items():
        content = content.replace(f"import com.linrun.interview.", f"import com.linrun.interview.")  # noop
        content = re.sub(
            rf"import com\.linrun\.interview\.[\w.]*\.repository\.{repo};\n",
            f"import {mapper_import};\n",
            content,
        )
        # field names like knowledgeBaseRepository -> knowledgeBaseMapper
        repo_var = repo[0].lower() + repo[1:]
        content = content.replace(repo_var, mapper_field)
        content = content.replace(f"private final {repo} ", f"private final {mapper_import.split('.')[-1]} ")
        content = content.replace(f"{repo} ", f"{mapper_import.split('.')[-1]} ")

    # common patterns
    content = content.replace(".findById(", ".selectById(")
    content = content.replace(".delete(", ".deleteById(")

    if content != orig:
        with open(path, "w", encoding="utf-8") as f:
            f.write(content)
        print("updated", path)


def main():
    for dirpath, _, files in os.walk(ROOT):
        if "repository" in dirpath.replace("\\", "/"):
            continue
        for fn in files:
            if fn.endswith(".java"):
                process_file(os.path.join(dirpath, fn))


if __name__ == "__main__":
    main()
