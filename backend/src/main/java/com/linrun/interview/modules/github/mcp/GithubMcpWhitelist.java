package com.linrun.interview.modules.github.mcp;

import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.modules.github.model.GithubFileStatus;
import com.linrun.interview.modules.github.model.GithubRepositoryEntity;
import com.linrun.interview.modules.github.model.GithubRepositoryFileEntity;
import com.linrun.interview.modules.github.security.GithubPathPolicy;
import java.util.Set;
import org.springframework.stereotype.Component;

/** MCP 工具、owner/repo/SHA/path 五重白名单，任何写操作或范围扩大都在调用前拒绝。 */
@Component
public class GithubMcpWhitelist {

  private static final Set<String> READ_ONLY_OPERATIONS = Set.of(
      "get_file_contents", "get_commit", "list_tree");

  public void validate(
      GithubMcpRequest request,
      GithubRepositoryEntity binding,
      GithubRepositoryFileEntity file
  ) {
    if (request == null || !READ_ONLY_OPERATIONS.contains(request.operation())) {
      throw forbidden("GitHub MCP 仅允许只读白名单操作");
    }
    if (!binding.getOwnerName().equalsIgnoreCase(request.owner())
        || !binding.getRepositoryName().equalsIgnoreCase(request.repository())
        || !binding.getFixedCommitSha().equalsIgnoreCase(request.commitSha())) {
      throw forbidden("GitHub MCP 请求超出绑定的 owner/repo/SHA");
    }
    if (!GithubPathPolicy.isSafe(request.path())
        || file == null
        || !file.getPath().equals(request.path())
        || !file.getCommitSha().equalsIgnoreCase(request.commitSha())
        || file.getStatus() != GithubFileStatus.SYNCED) {
      throw forbidden("GitHub MCP 文件不在已同步快照白名单");
    }
  }

  private BusinessException forbidden(String message) {
    return new BusinessException(ErrorCode.FORBIDDEN, message);
  }
}
