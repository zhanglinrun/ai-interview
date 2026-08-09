package com.linrun.interview.github.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.github.model.GithubRepositoryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GithubRepositoryMapper extends BaseMapper<GithubRepositoryEntity> {

  @Update("""
      UPDATE github_repository_bindings
      SET sync_status = 'SYNCING', sync_error = NULL, updated_at = CURRENT_TIMESTAMP(6)
      WHERE id = #{repositoryId} AND user_id = #{userId} AND sync_status <> 'SYNCING'
      """)
  int claimSync(@Param("userId") Long userId, @Param("repositoryId") Long repositoryId);
}
