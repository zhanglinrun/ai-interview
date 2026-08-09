package com.linrun.interview.github.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.linrun.interview.github.model.GithubRepositoryFileEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface GithubRepositoryFileMapper extends BaseMapper<GithubRepositoryFileEntity> {

  @Insert("""
      <script>
      INSERT INTO github_repository_files
        (user_id, repository_id, commit_sha, path, blob_sha, byte_size, language, file_kind,
         status, status_reason, default_included, content_hash, content_snapshot, created_at, updated_at)
      VALUES
      <foreach collection="files" item="file" separator=",">
        (#{file.userId}, #{file.repositoryId}, #{file.commitSha}, #{file.path}, #{file.blobSha},
         #{file.byteSize}, #{file.language}, #{file.fileKind}, #{file.status}, #{file.statusReason},
         #{file.defaultIncluded}, #{file.contentHash}, #{file.contentSnapshot},
         #{file.createdAt}, #{file.updatedAt})
      </foreach>
      </script>
      """)
  int batchInsert(@Param("files") List<GithubRepositoryFileEntity> files);
}
