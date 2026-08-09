package com.linrun.interview.github.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.github.config.GithubEvidenceProperties;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GitHub 官方远程 MCP 只读客户端")
class OfficialGithubRemoteMcpClientTest {

  private GithubEvidenceProperties properties;
  private ObjectMapper objectMapper;
  private OfficialGithubRemoteMcpClient client;

  @BeforeEach
  void setUp() {
    properties = new GithubEvidenceProperties();
    objectMapper = new ObjectMapper();
    client = new OfficialGithubRemoteMcpClient(
        properties, objectMapper, HttpClient.newHttpClient());
  }

  @Test
  @DisplayName("无 Token 时不发外部请求并明确回退")
  void shouldDegradeWithoutToken() {
    var result = client.execute(new GithubMcpRequest(
        "get_file_contents", "owner", "repo", "a".repeat(40), "README.md"));

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("解析 Streamable HTTP 的 SSE data JSON")
  void shouldParseSsePayload() throws Exception {
    var root = client.parseRpcPayload("""
        event: message
        data: {"jsonrpc":"2.0","id":2,"result":{"content":[]}}

        """);

    assertThat(root.path("id").asInt()).isEqualTo(2);
  }

  @Test
  @DisplayName("解包官方工具返回的 base64 文件正文")
  void shouldExtractBase64FileContent() throws Exception {
    String encoded = Base64.getEncoder().encodeToString(
        "public class Demo {}\n".getBytes(StandardCharsets.UTF_8));
    var root = objectMapper.readTree("""
        {"jsonrpc":"2.0","id":2,"result":{"content":[
          {"type":"text","text":"{\\"encoding\\":\\"base64\\",\\"content\\":\\"%s\\"}"}
        ]}}
        """.formatted(encoded));

    assertThat(client.extractFileContent(root))
        .contains("public class Demo {}\n");
  }

  @Test
  @DisplayName("拒绝把 MCP 端点改为非 GitHub 官方主机")
  void shouldRejectUntrustedEndpoint() {
    properties.setMcpEndpoint("https://example.com/mcp/");

    assertThatThrownBy(() -> new OfficialGithubRemoteMcpClient(
        properties, objectMapper, HttpClient.newHttpClient()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Endpoint");
  }
}
