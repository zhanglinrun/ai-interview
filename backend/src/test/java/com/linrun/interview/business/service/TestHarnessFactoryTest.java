package com.linrun.interview.business.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.business.vo.AlgorithmCatalogContent;
import com.linrun.interview.business.vo.AlgorithmCatalogContent.LanguageSpecDefinition;
import com.linrun.interview.business.constant.CodingLanguage;
import com.linrun.interview.business.entity.CodingProblemVersionEntity;
import com.linrun.interview.business.constant.TestSuiteType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("算法判题 Harness")
class TestHarnessFactoryTest {

  private ObjectMapper objectMapper;
  private TestHarnessFactory factory;
  private AlgorithmCatalogContent content;

  @TempDir
  Path tempDir;

  @BeforeEach
  void setUp() throws IOException {
    objectMapper = new ObjectMapper().findAndRegisterModules();
    factory = new TestHarnessFactory(objectMapper);
    try (InputStream input = getClass().getClassLoader()
        .getResourceAsStream("algorithm-content/hot100-v1.json")) {
      if (input == null) {
        throw new IllegalStateException("测试题库资源不存在");
      }
      content = objectMapper.readValue(input, AlgorithmCatalogContent.class);
    }
  }

  @Test
  @DisplayName("20 道 Java 21 参考实现应编译并通过全部公开与隐藏用例")
  void shouldCompileAndRunAllJavaReferences() throws Exception {
    assertThat(ToolProvider.getSystemJavaCompiler()).as("测试必须在 JDK 上运行").isNotNull();
    for (var enabled : content.enabledProblems()) {
      CodingProblemVersionEntity version = toEntity(enabled.version());
      LanguageSpecDefinition spec = language(enabled, CodingLanguage.JAVA21);
      for (TestSuiteType suite : TestSuiteType.values()) {
        var harness = factory.build(version, CodingLanguage.JAVA21, suite,
            spec.referenceSolution());
        Path output = tempDir.resolve(enabled.platformProblemId() + "-" + suite);
        Files.createDirectories(output);
        assertThat(compileAndRun(harness.sourceCode(), output))
            .as("problem=%s suite=%s", enabled.platformProblemId(), suite)
            .isEqualTo(harness.expectedOutput());
      }
    }
  }

  @Test
  @DisplayName("20 道 Java 21 初始模板应保持可编译")
  void shouldCompileAllJavaTemplates() throws Exception {
    for (var enabled : content.enabledProblems()) {
      CodingProblemVersionEntity version = toEntity(enabled.version());
      LanguageSpecDefinition spec = language(enabled, CodingLanguage.JAVA21);
      var harness = factory.build(version, CodingLanguage.JAVA21, TestSuiteType.PUBLIC,
          spec.template());
      Path output = tempDir.resolve(enabled.platformProblemId() + "-template");
      Files.createDirectories(output);
      compile(harness.sourceCode(), output);
    }
  }

  @Test
  @DisplayName("20 道 Python 3 参考实现应通过全部公开与隐藏用例")
  void shouldRunAllPythonReferencesWhenInterpreterExists() throws Exception {
    String python = findPython();
    Assumptions.assumeTrue(python != null, "当前环境无 Python 3，跳过解释器级验证");
    for (var enabled : content.enabledProblems()) {
      CodingProblemVersionEntity version = toEntity(enabled.version());
      LanguageSpecDefinition spec = language(enabled, CodingLanguage.PYTHON3);
      for (TestSuiteType suite : TestSuiteType.values()) {
        var harness = factory.build(version, CodingLanguage.PYTHON3, suite,
            spec.referenceSolution());
        Path script = tempDir.resolve(enabled.platformProblemId() + "-" + suite + ".py");
        Files.writeString(script, harness.sourceCode(), StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(python, script.toString())
            .redirectErrorStream(true).start();
        boolean finished = process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
          process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readNBytes(64 * 1024),
            StandardCharsets.UTF_8).strip();
        assertThat(finished).as("problem=%s suite=%s timeout", enabled.platformProblemId(), suite)
            .isTrue();
        assertThat(process.exitValue())
            .as("problem=%s suite=%s output=%s", enabled.platformProblemId(), suite, output)
            .isZero();
        assertThat(output).isEqualTo(harness.expectedOutput());
      }
    }
  }

  private CodingProblemVersionEntity toEntity(
      AlgorithmCatalogContent.ProblemVersionDefinition version
  ) throws IOException {
    return CodingProblemVersionEntity.builder()
        .languageSpecsJson(objectMapper.writeValueAsString(version.languages()))
        .publicTestsJson(objectMapper.writeValueAsString(version.publicTests()))
        .hiddenTestsJson(objectMapper.writeValueAsString(version.hiddenTests()))
        .enabled(true)
        .javaEnabled(true)
        .pythonEnabled(true)
        .build();
  }

  private LanguageSpecDefinition language(
      AlgorithmCatalogContent.EnabledProblemDefinition enabled,
      CodingLanguage language
  ) {
    return enabled.version().languages().stream()
        .filter(item -> item.language() == language)
        .findFirst().orElseThrow();
  }

  private String compileAndRun(String source, Path output) throws Exception {
    compile(source, output);
    PrintStream original = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try (URLClassLoader loader = new URLClassLoader(
        new java.net.URL[]{output.toUri().toURL()}, null);
        PrintStream print = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
      System.setOut(print);
      Class<?> main = Class.forName("Main", true, loader);
      var method = main.getDeclaredMethod("main", String[].class);
      method.setAccessible(true);
      method.invoke(null, (Object) new String[0]);
    } finally {
      System.setOut(original);
    }
    return captured.toString(StandardCharsets.UTF_8);
  }

  private void compile(String source, Path output) throws IOException {
    var compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(
        diagnostics, null, StandardCharsets.UTF_8)) {
      JavaFileObject file = new StringSource(source);
      boolean success = compiler.getTask(null, fileManager, diagnostics,
          List.of("-d", output.toString(), "-encoding", "UTF-8"), null, List.of(file)).call();
      assertThat(success).as(diagnostics.getDiagnostics().toString()).isTrue();
    }
  }

  private String findPython() {
    List<String> candidates = System.getProperty("os.name", "").toLowerCase().contains("win")
        ? List.of("python", "python3") : List.of("python3", "python");
    for (String candidate : candidates) {
      try {
        Process process = new ProcessBuilder(candidate, "--version")
            .redirectErrorStream(true).start();
        if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0) {
          return candidate;
        }
        process.destroyForcibly();
      } catch (IOException | InterruptedException ignored) {
        if (ignored instanceof InterruptedException) {
          Thread.currentThread().interrupt();
          return null;
        }
      }
    }
    return null;
  }

  private static final class StringSource extends SimpleJavaFileObject {
    private final String source;

    private StringSource(String source) {
      super(URI.create("string:///Main.java"), Kind.SOURCE);
      this.source = source;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return source;
    }
  }
}
