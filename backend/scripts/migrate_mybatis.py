#!/usr/bin/env python3
"""Generate MyBatis-Plus mappers and convert entity files from JPA to MyBatis-Plus."""
import os
import re

BACKEND = r"e:\javaproject\ai-interview\backend\src\main\java\com\linrun\interview"

MAPPERS = [
    ("modules/user", "UserEntity", "UserMapper", "Long"),
    ("modules/resume", "ResumeEntity", "ResumeMapper", "Long"),
    ("modules/resume", "ResumeAnalysisEntity", "ResumeAnalysisMapper", "Long"),
    ("modules/knowledgebase", "KnowledgeBaseEntity", "KnowledgeBaseMapper", "Long"),
    ("modules/knowledgebase", "KnowledgeBaseVersionEntity", "KnowledgeBaseVersionMapper", "Long"),
    ("modules/knowledgebase", "KnowledgeBaseSegmentEntity", "KnowledgeBaseSegmentMapper", "Long"),
    ("modules/knowledgebase", "KnowledgeBaseDataTableEntity", "KnowledgeBaseDataTableMapper", "Long"),
    ("modules/knowledgebase", "RagChatSessionEntity", "RagChatSessionMapper", "Long"),
    ("modules/knowledgebase", "RagChatMessageEntity", "RagChatMessageMapper", "Long"),
    ("modules/knowledgebase", "RagEvaluationRunEntity", "RagEvaluationRunMapper", "Long"),
    ("modules/knowledgebase", "RagQueryTraceEntity", "RagQueryTraceMapper", "Long"),
    ("modules/interview", "InterviewSessionEntity", "InterviewSessionMapper", "Long"),
    ("modules/interview", "InterviewAnswerEntity", "InterviewAnswerMapper", "Long"),
    ("modules/interviewschedule", "InterviewScheduleEntity", "InterviewScheduleMapper", "Long"),
    ("modules/voiceinterview", "VoiceInterviewSessionEntity", "VoiceInterviewSessionMapper", "Long"),
    ("modules/voiceinterview", "VoiceInterviewMessageEntity", "VoiceInterviewMessageMapper", "Long"),
    ("modules/voiceinterview", "VoiceInterviewEvaluationEntity", "VoiceInterviewEvaluationMapper", "Long"),
    ("modules/llmprovider", "LlmProviderEntity", "LlmProviderMapper", "String"),
    ("modules/llmprovider", "LlmGlobalSettingEntity", "LlmGlobalSettingMapper", "Long"),
]

JPA_IMPORTS = re.compile(
    r"import jakarta\.persistence\.[^;]+;\n"
)

JPA_ANNOTATIONS = re.compile(
    r"@(Entity|Table|Id|GeneratedValue|Column|Enumerated|ManyToOne|OneToMany|ManyToMany|"
    r"JoinColumn|JoinTable|FetchType|CascadeType|Index|UniqueConstraint|PrePersist|"
    r"PreUpdate|PostLoad|OrderBy)\b[^{;]*(?:\([^)]*\))?[^{;]*;?\n?",
    re.MULTILINE,
)

TABLE_BLOCK = re.compile(r"@Table\([^)]*\)\s*", re.DOTALL)


def create_mapper(module_path, entity, mapper_name, id_type):
    pkg = f"com.linrun.interview.{module_path.replace('/', '.')}"
    mapper_dir = os.path.join(BACKEND, module_path.replace("/", os.sep), "mapper")
    os.makedirs(mapper_dir, exist_ok=True)
    path = os.path.join(mapper_dir, f"{mapper_name}.java")
    if os.path.exists(path):
        return
    content = f"""package {pkg}.mapper;

import {pkg}.model.{entity};
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface {mapper_name} extends BaseMapper<{entity}> {{
}}
"""
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"Created {path}")


def convert_entity(module_path, entity_name):
    model_dir = os.path.join(BACKEND, module_path.replace("/", os.sep), "model")
    path = os.path.join(model_dir, f"{entity_name}.java")
    if not os.path.exists(path):
        print(f"SKIP {path}")
        return
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    if "com.baomidou.mybatisplus.annotation.TableName" in content:
        print(f"Already converted {path}")
        return

    # Determine table name from @Table
    table_match = re.search(r'@Table\(name\s*=\s*"([^"]+)"', content)
    table_name = table_match.group(1) if table_match else entity_name.replace("Entity", "").lower()

    pkg = f"com.linrun.interview.{module_path.replace('/', '.')}"

    # Remove JPA imports and annotations
    content = JPA_IMPORTS.sub("", content)
    content = TABLE_BLOCK.sub("", content)
    content = JPA_ANNOTATIONS.sub("", content)

    # Add MyBatis imports after package
    mp_imports = """import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
"""
    content = re.sub(
        r"(package [^;]+;\n)",
        r"\1\n" + mp_imports,
        content,
        count=1,
    )

    # Add @TableName after class-level annotations
    if "@TableName" not in content:
        content = re.sub(
            r"((?:@\w+(?:\([^)]*\))?\s*)*)(public class )",
            rf"\1@TableName(\"{table_name}\")\n\2",
            content,
            count=1,
        )

    # Fix @Id fields
    if entity_name == "KnowledgeBaseVersionEntity":
        content = re.sub(
            r"(\s+)private Long versionId;",
            r"\1@TableId(value = \"version_id\", type = IdType.AUTO)\n\1private Long versionId;",
            content,
        )
    elif entity_name == "LlmProviderEntity":
        content = re.sub(
            r"(\s+)private String id;",
            r"\1@TableId(type = IdType.INPUT)\n\1private String id;",
            content,
        )
    else:
        content = re.sub(
            r"(\s+)private Long id;",
            r"\1@TableId(type = IdType.AUTO)\n\1private Long id;",
            content,
            count=1,
        )

    with open(path, "w", encoding="utf-8") as f:
        f.write(content)
    print(f"Converted {path}")


def main():
    for module, entity, mapper, id_type in MAPPERS:
        create_mapper(module, entity, mapper, id_type)
        convert_entity(module, entity)

    # Rag session join table mapper
    join_dir = os.path.join(BACKEND, "modules", "knowledgebase", "mapper")
    join_path = os.path.join(join_dir, "RagSessionKnowledgeBaseMapper.java")
    if not os.path.exists(join_path):
        join_content = (
            "package com.linrun.interview.modules.knowledgebase.mapper;\n\n"
            "import org.apache.ibatis.annotations.Delete;\n"
            "import org.apache.ibatis.annotations.Insert;\n"
            "import org.apache.ibatis.annotations.Mapper;\n"
            "import org.apache.ibatis.annotations.Param;\n"
            "import org.apache.ibatis.annotations.Select;\n\n"
            "import java.util.List;\n\n"
            "@Mapper\n"
            "public interface RagSessionKnowledgeBaseMapper {\n\n"
            "  @Insert(\"INSERT INTO rag_session_knowledge_bases "
            "(session_id, knowledge_base_id) VALUES (#{sessionId}, #{knowledgeBaseId})\")\n"
            "  int insertLink(@Param(\"sessionId\") Long sessionId, "
            "@Param(\"knowledgeBaseId\") Long knowledgeBaseId);\n\n"
            "  @Delete(\"DELETE FROM rag_session_knowledge_bases WHERE session_id = #{sessionId}\")\n"
            "  int deleteBySessionId(@Param(\"sessionId\") Long sessionId);\n\n"
            "  @Select(\"SELECT knowledge_base_id FROM rag_session_knowledge_bases "
            "WHERE session_id = #{sessionId}\")\n"
            "  List<Long> selectKnowledgeBaseIdsBySessionId(@Param(\"sessionId\") Long sessionId);\n"
            "}\n"
        )
        with open(join_path, "w", encoding="utf-8") as f:
            f.write(join_content)
        print(f"Created {join_path}")


if __name__ == "__main__":
    main()
