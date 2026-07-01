#!/usr/bin/env python3
import os
import re

ROOT = r"e:\javaproject\ai-interview\backend\src\main\java\com\linrun\interview\modules"

MAPPINGS = [
    ("UserRepository", "com.linrun.interview.modules.user.mapper.UserMapper", "userMapper"),
    ("ResumeRepository", "com.linrun.interview.modules.resume.mapper.ResumeMapper", "resumeMapper"),
    ("ResumeAnalysisRepository", "com.linrun.interview.modules.resume.mapper.ResumeAnalysisMapper", "resumeAnalysisMapper"),
    ("KnowledgeBaseRepository", "com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseEntityMapper", "knowledgeBaseEntityMapper"),
    ("KnowledgeBaseVersionRepository", "com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseVersionMapper", "knowledgeBaseVersionMapper"),
    ("KnowledgeBaseSegmentRepository", "com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseSegmentMapper", "knowledgeBaseSegmentMapper"),
    ("KnowledgeBaseDataTableRepository", "com.linrun.interview.modules.knowledgebase.mapper.KnowledgeBaseDataTableMapper", "knowledgeBaseDataTableMapper"),
    ("RagChatSessionRepository", "com.linrun.interview.modules.knowledgebase.mapper.RagChatSessionMapper", "ragChatSessionMapper"),
    ("RagChatMessageRepository", "com.linrun.interview.modules.knowledgebase.mapper.RagChatMessageMapper", "ragChatMessageMapper"),
    ("RagEvaluationRunRepository", "com.linrun.interview.modules.knowledgebase.mapper.RagEvaluationRunMapper", "ragEvaluationRunMapper"),
    ("RagQueryTraceRepository", "com.linrun.interview.modules.knowledgebase.mapper.RagQueryTraceMapper", "ragQueryTraceMapper"),
    ("InterviewSessionRepository", "com.linrun.interview.modules.interview.mapper.InterviewSessionMapper", "interviewSessionMapper"),
    ("InterviewAnswerRepository", "com.linrun.interview.modules.interview.mapper.InterviewAnswerMapper", "interviewAnswerMapper"),
    ("InterviewScheduleRepository", "com.linrun.interview.modules.interviewschedule.mapper.InterviewScheduleMapper", "interviewScheduleMapper"),
    ("VoiceInterviewSessionRepository", "com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewSessionMapper", "voiceInterviewSessionMapper"),
    ("VoiceInterviewMessageRepository", "com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewMessageMapper", "voiceInterviewMessageMapper"),
    ("VoiceInterviewEvaluationRepository", "com.linrun.interview.modules.voiceinterview.mapper.VoiceInterviewEvaluationMapper", "voiceInterviewEvaluationMapper"),
    ("LlmProviderRepository", "com.linrun.interview.modules.llmprovider.mapper.LlmProviderMapper", "llmProviderMapper"),
    ("LlmGlobalSettingRepository", "com.linrun.interview.modules.llmprovider.mapper.LlmGlobalSettingMapper", "llmGlobalSettingMapper"),
]


def process(path):
    with open(path, encoding="utf-8") as f:
        c = f.read()
    if ".repository." not in c and "Repository" not in c:
        return
    orig = c
    for repo, imp, var in MAPPINGS:
        c = re.sub(rf"import com\.linrun\.interview\.[\w.]*\.repository\.{repo};\n", f"import {imp};\n", c)
        repo_var = repo[0].lower() + repo[1:]
        c = c.replace(repo_var, var)
        mapper_cls = imp.split(".")[-1]
        c = c.replace(f"private final {repo} ", f"private final {mapper_cls} ")
    c = c.replace(".findById(", ".selectById(")
    c = re.sub(r"(\w+Mapper)\.delete\((\w+)\)", r"\1.deleteById(\2.getId())", c)
    c = re.sub(r"(\w+Mapper)\.save\(([^)]+)\)", r"MapperUtils.save(\1, \2)", c)
    c = re.sub(r"(\w+Mapper)\.saveAll\(([^)]+)\)", r"\2.forEach(e -> MapperUtils.save(\1, e))", c)
    if "MapperUtils" in c and "import com.linrun.interview.common.mybatis.MapperUtils" not in c:
        c = c.replace("package ", "import com.linrun.interview.common.mybatis.MapperUtils;\n\npackage ", 1)
    if c != orig:
        with open(path, "w", encoding="utf-8") as f:
            f.write(c)
        print("ok", os.path.basename(path))


for dp, _, fs in os.walk(ROOT):
    if "\\repository\\" in dp or "/repository/" in dp:
        continue
    for fn in fs:
        if fn.endswith(".java"):
            process(os.path.join(dp, fn))
