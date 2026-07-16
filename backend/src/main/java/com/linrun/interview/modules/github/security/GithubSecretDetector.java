package com.linrun.interview.modules.github.security;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 保守的敏感信息检测器。命中时整个文件正文都不持久化、不向量化，只保留安全告警状态。
 */
@Component
public class GithubSecretDetector {

  private static final List<Pattern> SECRET_PATTERNS = List.of(
      Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
      Pattern.compile("(?i)\\bAKIA[0-9A-Z]{16}\\b"),
      Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
      Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"),
      Pattern.compile("(?i)(?:api[_-]?key|access[_-]?token|secret[_-]?key|password)"
          + "\\s*[:=]\\s*['\"]?[A-Za-z0-9+/=_-]{12,}")
  );

  private static final List<String> SENSITIVE_NAMES = List.of(
      ".env", ".npmrc", ".pypirc", "id_rsa", "id_ed25519", "credentials",
      "secrets.yml", "secrets.yaml", "application-prod.yml", "application-prod.yaml");

  private static final List<String> SENSITIVE_SUFFIXES = List.of(
      ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore");

  public boolean isSensitivePath(String path) {
    String normalized = path.toLowerCase(Locale.ROOT);
    String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
    if (SENSITIVE_NAMES.contains(fileName) || fileName.startsWith(".env.")) {
      return true;
    }
    return SENSITIVE_SUFFIXES.stream().anyMatch(fileName::endsWith);
  }

  public boolean containsLikelySecret(String content) {
    if (content == null || content.isEmpty()) {
      return false;
    }
    return SECRET_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(content).find());
  }
}
