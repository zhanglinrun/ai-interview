package com.linrun.interview.modules.knowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.modules.knowledgebase.model.KnowledgeBaseVersionEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeBaseVersionMapper extends BaseMapper<KnowledgeBaseVersionEntity> {

  @Delete("DELETE FROM knowledge_base_version WHERE doc_id = #{docId}")
  int physicalDeleteByDocId(@Param("docId") Long docId);

  @Delete("DELETE FROM knowledge_base_version WHERE version_id = #{versionId}")
  int physicalDeleteByVersionId(@Param("versionId") Long versionId);
}
