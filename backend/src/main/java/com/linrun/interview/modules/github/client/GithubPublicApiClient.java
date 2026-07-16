package com.linrun.interview.modules.github.client;

import java.util.List;

/** 可替换的 GitHub 公共只读 REST Client；业务层不依赖具体 HTTP 库。 */
public interface GithubPublicApiClient {

  RepositoryDescriptor getPublicRepository(String owner, String repository);

  String resolveCommitSha(String owner, String repository, String ref);

  RepositoryTree getTree(String owner, String repository, String commitSha);

  BlobContent getBlob(String owner, String repository, String blobSha, long maxDecodedBytes);

  record RepositoryDescriptor(
      String owner,
      String repository,
      String htmlUrl,
      String defaultBranch,
      long sizeKb,
      boolean privateRepository
  ) {
  }

  record RepositoryTree(List<TreeEntry> entries, boolean truncated) {
    public RepositoryTree {
      entries = entries == null ? List.of() : List.copyOf(entries);
    }
  }

  record TreeEntry(String path, String type, String sha, long size) {
    public boolean isBlob() {
      return "blob".equals(type);
    }
  }

  record BlobContent(String sha, byte[] bytes) {
    public BlobContent {
      bytes = bytes == null ? new byte[0] : bytes.clone();
    }

    @Override
    public byte[] bytes() {
      return bytes.clone();
    }
  }
}
