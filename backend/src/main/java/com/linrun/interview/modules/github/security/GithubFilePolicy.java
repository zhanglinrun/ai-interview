package com.linrun.interview.modules.github.security;

import com.linrun.interview.modules.github.client.GithubPublicApiClient.TreeEntry;
import com.linrun.interview.modules.github.config.GithubEvidenceProperties;
import com.linrun.interview.modules.github.model.GithubFileDecision;
import com.linrun.interview.modules.github.model.GithubFileKind;
import com.linrun.interview.modules.github.model.GithubFileStatus;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 在下载正文前生成受限文件清单。依赖目录、构建产物、二进制、生成代码和敏感路径默认硬排除；
 * {@code pom.xml/package.json} 等依赖声明属于工程证据，仍允许同步。
 */
@Component
public class GithubFilePolicy {

  private static final Pattern BLOB_SHA = Pattern.compile("[a-fA-F0-9]{40}");

  private static final Set<String> DEPENDENCY_DIRECTORIES = Set.of(
      "node_modules", "vendor", ".venv", "venv", "site-packages", "third_party",
      "third-party", "pods");
  private static final Set<String> BUILD_DIRECTORIES = Set.of(
      "target", "build", "dist", "out", "bin", "obj", ".next", ".nuxt", "coverage",
      ".gradle", ".idea", ".vscode", ".git");
  private static final Set<String> GENERATED_DIRECTORIES = Set.of(
      "generated", "generated-sources", "gen", "codegen");
  private static final Set<String> BINARY_EXTENSIONS = Set.of(
      "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "pdf", "zip", "gz", "tar",
      "7z", "rar", "jar", "war", "class", "so", "dll", "dylib", "exe", "bin", "woff",
      "woff2", "ttf", "otf", "mp3", "mp4", "wav", "avi", "mov", "sqlite", "db");
  private static final Set<String> GENERATED_FILES = Set.of(
      "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "poetry.lock", "cargo.lock",
      "composer.lock");
  private static final Map<String, String> LANGUAGES = Map.ofEntries(
      Map.entry("java", "Java"), Map.entry("kt", "Kotlin"), Map.entry("kts", "Kotlin"),
      Map.entry("py", "Python"), Map.entry("js", "JavaScript"), Map.entry("jsx", "JavaScript"),
      Map.entry("ts", "TypeScript"), Map.entry("tsx", "TypeScript"), Map.entry("go", "Go"),
      Map.entry("rs", "Rust"), Map.entry("c", "C"), Map.entry("h", "C"),
      Map.entry("cc", "C++"), Map.entry("cpp", "C++"), Map.entry("hpp", "C++"),
      Map.entry("cs", "C#"), Map.entry("sql", "SQL"), Map.entry("sh", "Shell"),
      Map.entry("ps1", "PowerShell"), Map.entry("rb", "Ruby"), Map.entry("php", "PHP"),
      Map.entry("scala", "Scala"), Map.entry("swift", "Swift"), Map.entry("vue", "Vue"),
      Map.entry("svelte", "Svelte"), Map.entry("xml", "XML"), Map.entry("json", "JSON"),
      Map.entry("yml", "YAML"), Map.entry("yaml", "YAML"), Map.entry("toml", "TOML"),
      Map.entry("properties", "Properties"), Map.entry("md", "Markdown"),
      Map.entry("adoc", "AsciiDoc"), Map.entry("gradle", "Gradle"));

  private final GithubEvidenceProperties properties;
  private final GithubSecretDetector secretDetector;

  public GithubFilePolicy(
      GithubEvidenceProperties properties,
      GithubSecretDetector secretDetector
  ) {
    this.properties = properties;
    this.secretDetector = secretDetector;
  }

  public GithubFileDecision classify(TreeEntry entry) {
    String path = entry.path();
    if (!entry.isBlob() || !GithubPathPolicy.isSafe(path)) {
      return excluded(GithubFileStatus.EXCLUDED_INVALID_PATH, "仓库路径非法或不是文件");
    }
    if (entry.sha() == null || !BLOB_SHA.matcher(entry.sha()).matches()) {
      return excluded(GithubFileStatus.EXCLUDED_INVALID_PATH, "GitHub Blob SHA 非法");
    }
    if (secretDetector.isSensitivePath(path)) {
      return excluded(GithubFileStatus.EXCLUDED_SENSITIVE_PATH, "敏感文件路径不进入证据库");
    }
    String normalized = path.toLowerCase(Locale.ROOT);
    String[] segments = normalized.split("/");
    for (String segment : segments) {
      if (DEPENDENCY_DIRECTORIES.contains(segment)) {
        return excluded(GithubFileStatus.EXCLUDED_DEPENDENCY, "依赖目录不进入证据库");
      }
      if (BUILD_DIRECTORIES.contains(segment)) {
        return excluded(GithubFileStatus.EXCLUDED_BUILD_OUTPUT, "构建产物目录不进入证据库");
      }
      if (GENERATED_DIRECTORIES.contains(segment)) {
        return excluded(GithubFileStatus.EXCLUDED_GENERATED, "生成代码目录不进入证据库");
      }
    }
    String fileName = segments[segments.length - 1];
    String extension = extension(fileName);
    if (BINARY_EXTENSIONS.contains(extension)) {
      return excluded(GithubFileStatus.EXCLUDED_BINARY, "二进制文件不进入证据库");
    }
    if (GENERATED_FILES.contains(fileName) || fileName.endsWith(".min.js")
        || fileName.endsWith(".min.css") || fileName.endsWith(".generated.java")) {
      return excluded(GithubFileStatus.EXCLUDED_GENERATED, "生成文件不进入证据库");
    }
    if (entry.size() > properties.getMaxFileBytes()) {
      return excluded(GithubFileStatus.EXCLUDED_TOO_LARGE, "文件超过单文件大小上限");
    }

    GithubFileKind kind = kind(normalized, fileName);
    String language = language(fileName, extension);
    if (language == null && kind == GithubFileKind.OTHER) {
      return excluded(GithubFileStatus.EXCLUDED_UNSUPPORTED, "不支持的文本文件类型");
    }
    int priority = priority(kind, normalized);
    return new GithubFileDecision(
        GithubFileStatus.ELIGIBLE,
        kind,
        language == null ? "Text" : language,
        null,
        priority);
  }

  private GithubFileKind kind(String path, String fileName) {
    if (fileName.equals("readme") || fileName.startsWith("readme.")) {
      return GithubFileKind.README;
    }
    if (path.startsWith(".github/workflows/") || fileName.equals("jenkinsfile")
        || fileName.equals(".gitlab-ci.yml")) {
      return GithubFileKind.CI;
    }
    if (fileName.equals("pom.xml") || fileName.equals("build.gradle")
        || fileName.equals("build.gradle.kts") || fileName.equals("settings.gradle")
        || fileName.equals("settings.gradle.kts") || fileName.equals("package.json")
        || fileName.equals("pyproject.toml") || fileName.equals("requirements.txt")
        || fileName.equals("go.mod") || fileName.equals("cargo.toml")
        || fileName.equals("dockerfile") || fileName.startsWith("docker-compose")) {
      return GithubFileKind.BUILD;
    }
    if (path.contains("/test/") || path.contains("/tests/") || path.startsWith("test/")
        || path.startsWith("tests/") || fileName.endsWith("test.java")
        || fileName.endsWith("test.py") || fileName.endsWith(".spec.ts")
        || fileName.endsWith(".test.ts") || fileName.endsWith(".spec.js")) {
      return GithubFileKind.TEST;
    }
    if (path.startsWith("docs/") || fileName.endsWith(".md") || fileName.endsWith(".adoc")) {
      return GithubFileKind.DOCUMENTATION;
    }
    if (fileName.endsWith(".yml") || fileName.endsWith(".yaml")
        || fileName.endsWith(".properties") || fileName.endsWith(".toml")
        || fileName.endsWith(".json") || fileName.endsWith(".xml")) {
      return GithubFileKind.CONFIG;
    }
    return GithubFileKind.SOURCE;
  }

  private int priority(GithubFileKind kind, String path) {
    int base = switch (kind) {
      case README -> 100;
      case BUILD, CI -> 90;
      case SOURCE -> 80;
      case TEST -> 70;
      case CONFIG -> 60;
      case DOCUMENTATION -> 50;
      case OTHER -> 10;
    };
    return path.contains("/main/") || path.startsWith("src/") ? base + 5 : base;
  }

  private String language(String fileName, String extension) {
    if (fileName.equals("dockerfile")) {
      return "Dockerfile";
    }
    if (fileName.equals("jenkinsfile")) {
      return "Groovy";
    }
    return LANGUAGES.get(extension);
  }

  private String extension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1);
  }

  private GithubFileDecision excluded(GithubFileStatus status, String reason) {
    return new GithubFileDecision(status, GithubFileKind.OTHER, null, reason, 0);
  }
}
