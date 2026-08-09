package com.linrun.interview.business.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linrun.interview.common.exception.BusinessException;
import com.linrun.interview.common.exception.ErrorCode;
import com.linrun.interview.business.vo.AlgorithmCatalogContent.LanguageSpecDefinition;
import com.linrun.interview.business.vo.AlgorithmCatalogContent.TestCaseDefinition;
import com.linrun.interview.business.constant.CodingLanguage;
import com.linrun.interview.business.entity.CodingProblemVersionEntity;
import com.linrun.interview.business.constant.TestSuiteType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 将用户函数与仓库内版本化测试驱动组合；只返回给 JudgeClient，绝不执行或写日志。
 */
@Component
public class TestHarnessFactory {

  private static final TypeReference<List<LanguageSpecDefinition>> LANGUAGE_LIST =
      new TypeReference<>() {
      };
  private static final TypeReference<List<TestCaseDefinition>> TEST_LIST =
      new TypeReference<>() {
      };

  private final ObjectMapper objectMapper;

  public TestHarnessFactory(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public TestHarness build(
      CodingProblemVersionEntity version,
      CodingLanguage language,
      TestSuiteType suiteType,
      String userSource
  ) {
    if (userSource == null || userSource.isBlank()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "源码不能为空");
    }
    LanguageSpecDefinition spec = languageSpec(version, language);
    List<TestCaseDefinition> tests = testCases(version, suiteType);
    if (tests.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "当前题目没有可用测试用例");
    }
    String expectedOutput = "AIJUDGE_RESULT:" + tests.size() + "/" + tests.size();
    String source = language == CodingLanguage.JAVA21
        ? buildJava(spec, tests, userSource)
        : buildPython(spec, tests, userSource);
    return new TestHarness(source, expectedOutput, tests.size());
  }

  public LanguageSpecDefinition languageSpec(
      CodingProblemVersionEntity version,
      CodingLanguage language
  ) {
    try {
      return objectMapper.readValue(version.getLanguageSpecsJson(), LANGUAGE_LIST).stream()
          .filter(item -> item.language() == language && Boolean.TRUE.equals(item.enabled()))
          .findFirst()
          .orElseThrow(() -> new BusinessException(
              ErrorCode.BAD_REQUEST, "当前题目未启用所选语言"));
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "题目语言配置损坏", e);
    }
  }

  private List<TestCaseDefinition> testCases(
      CodingProblemVersionEntity version,
      TestSuiteType suiteType
  ) {
    String json = suiteType == TestSuiteType.PUBLIC
        ? version.getPublicTestsJson() : version.getHiddenTestsJson();
    try {
      return objectMapper.readValue(json, TEST_LIST);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "题目测试配置损坏", e);
    }
  }

  private String buildJava(
      LanguageSpecDefinition spec,
      List<TestCaseDefinition> tests,
      String userSource
  ) {
    StringBuilder source = new StringBuilder(8192)
        .append("import java.util.*;\nimport java.io.*;\n")
        .append(userSource).append('\n')
        .append(JAVA_SUPPORT).append('\n')
        .append("class Main { public static void main(String[] args) {\n")
        .append("PrintStream __out = System.out; int __passed = 0;\n")
        .append("System.setOut(new PrintStream(OutputStream.nullOutputStream()));\n")
        .append("try {\n");
    for (TestCaseDefinition test : tests) {
      source.append("try { Object __actual = new Solution().")
          .append(spec.functionName()).append('(')
          .append(javaArguments(test.arguments(), spec.parameterTypes()))
          .append("); Object __expected = ")
          .append(javaValue(test.expected(), spec.returnType()))
          .append("; if (JudgeSupport.same(__actual, __expected, \"")
          .append(spec.returnType()).append("\", \"")
          .append(comparisonMode(test)).append("\")) __passed++; } ")
          .append("catch (Throwable __ignored) { }\n");
    }
    return source.append("} finally { System.setOut(__out); }\n")
        .append("__out.print(\"AIJUDGE_RESULT:\" + __passed + \"/")
        .append(tests.size()).append("\"); } }\n")
        .toString();
  }

  private String buildPython(
      LanguageSpecDefinition spec,
      List<TestCaseDefinition> tests,
      String userSource
  ) {
    StringBuilder source = new StringBuilder(8192)
        .append(PYTHON_SUPPORT).append('\n')
        .append(userSource).append('\n')
        .append("__passed = 0\n__solution = Solution()\n")
        .append("with contextlib.redirect_stdout(io.StringIO()):\n");
    for (TestCaseDefinition test : tests) {
      source.append("    try:\n        __actual = __solution.")
          .append(spec.functionName()).append('(')
          .append(pythonArguments(test.arguments(), spec.parameterTypes()))
          .append(")\n        __expected = ")
          .append(pythonValue(test.expected(), spec.returnType()))
          .append("\n        if _same(__actual, __expected, ")
          .append(pythonString(spec.returnType())).append(", ")
          .append(pythonString(comparisonMode(test))).append("):\n")
          .append("            __passed += 1\n    except BaseException:\n        pass\n");
    }
    return source.append("print(f\"AIJUDGE_RESULT:{__passed}/")
        .append(tests.size()).append("\")\n")
        .toString();
  }

  private String javaArguments(JsonNode arguments, List<String> types) {
    if (!arguments.isArray() || arguments.size() != types.size()) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "题目参数配置损坏");
    }
    StringBuilder value = new StringBuilder();
    for (int i = 0; i < types.size(); i++) {
      if (i > 0) {
        value.append(", ");
      }
      value.append(javaValue(arguments.get(i), types.get(i)));
    }
    return value.toString();
  }

  private String pythonArguments(JsonNode arguments, List<String> types) {
    if (!arguments.isArray() || arguments.size() != types.size()) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "题目参数配置损坏");
    }
    StringBuilder value = new StringBuilder();
    for (int i = 0; i < types.size(); i++) {
      if (i > 0) {
        value.append(", ");
      }
      value.append(pythonValue(arguments.get(i), types.get(i)));
    }
    return value.toString();
  }

  private String javaValue(JsonNode value, String type) {
    return switch (type) {
      case "INT" -> Integer.toString(value.asInt());
      case "BOOLEAN" -> Boolean.toString(value.asBoolean());
      case "STRING" -> jsonString(value.asText());
      case "INT_ARRAY" -> "new int[]{" + joinJavaInts(value) + "}";
      case "INT_MATRIX" -> "new int[][]{" + joinJavaIntRows(value) + "}";
      case "CHAR_MATRIX" -> "new char[][]{" + joinJavaCharRows(value) + "}";
      case "LIST_NODE" -> "JudgeSupport.buildList(new int[]{" + joinJavaInts(value) + "})";
      case "TREE_NODE" -> "JudgeSupport.buildTree(new Integer[]{" + joinJavaIntegers(value) + "})";
      case "LIST_INT" -> "List.of(" + joinJavaInts(value) + ")";
      case "LIST_LIST_INT" -> "List.of(" + joinJavaListRows(value) + ")";
      default -> throw new BusinessException(
          ErrorCode.INTERNAL_ERROR, "不支持的 Java 判题类型: " + type);
    };
  }

  private String pythonValue(JsonNode value, String type) {
    return switch (type) {
      case "INT" -> Integer.toString(value.asInt());
      case "BOOLEAN" -> value.asBoolean() ? "True" : "False";
      case "STRING" -> pythonString(value.asText());
      case "INT_ARRAY", "INT_MATRIX", "LIST_INT", "LIST_LIST_INT" -> value.toString();
      case "CHAR_MATRIX" -> "[list(x) for x in " + value + "]";
      case "LIST_NODE" -> "_build_list(" + value + ")";
      case "TREE_NODE" -> "_build_tree(" + pythonJson(value) + ")";
      default -> throw new BusinessException(
          ErrorCode.INTERNAL_ERROR, "不支持的 Python 判题类型: " + type);
    };
  }

  private String joinJavaInts(JsonNode array) {
    requireArray(array);
    StringBuilder value = new StringBuilder();
    for (int i = 0; i < array.size(); i++) {
      if (i > 0) {
        value.append(',');
      }
      value.append(array.get(i).asInt());
    }
    return value.toString();
  }

  private String joinJavaIntegers(JsonNode array) {
    requireArray(array);
    StringBuilder value = new StringBuilder();
    for (int i = 0; i < array.size(); i++) {
      if (i > 0) {
        value.append(',');
      }
      value.append(array.get(i).isNull() ? "null" : array.get(i).asInt());
    }
    return value.toString();
  }

  private String joinJavaIntRows(JsonNode matrix) {
    requireArray(matrix);
    StringBuilder value = new StringBuilder();
    for (int i = 0; i < matrix.size(); i++) {
      if (i > 0) {
        value.append(',');
      }
      value.append("new int[]{").append(joinJavaInts(matrix.get(i))).append('}');
    }
    return value.toString();
  }

  private String joinJavaCharRows(JsonNode rows) {
    requireArray(rows);
    StringBuilder value = new StringBuilder();
    for (int i = 0; i < rows.size(); i++) {
      if (i > 0) {
        value.append(',');
      }
      value.append(jsonString(rows.get(i).asText())).append(".toCharArray()");
    }
    return value.toString();
  }

  private String joinJavaListRows(JsonNode rows) {
    requireArray(rows);
    StringBuilder value = new StringBuilder();
    for (int i = 0; i < rows.size(); i++) {
      if (i > 0) {
        value.append(',');
      }
      value.append("List.of(").append(joinJavaInts(rows.get(i))).append(')');
    }
    return value.toString();
  }

  private void requireArray(JsonNode value) {
    if (value == null || !value.isArray()) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "题目测试值配置损坏");
    }
  }

  private String comparisonMode(TestCaseDefinition test) {
    return test.comparisonMode() == null || test.comparisonMode().isBlank()
        ? "ORDERED" : test.comparisonMode();
  }

  private String jsonString(String value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "题目字符串配置损坏", e);
    }
  }

  private String pythonString(String value) {
    return jsonString(value);
  }

  private String pythonJson(JsonNode value) {
    return value.toString().replace("null", "None")
        .replace("true", "True").replace("false", "False");
  }

  public record TestHarness(String sourceCode, String expectedOutput, int totalCount) {
  }

  private static final String JAVA_SUPPORT = """
      class ListNode { int val; ListNode next; ListNode(int val) { this.val = val; } }
      class TreeNode { int val; TreeNode left; TreeNode right; TreeNode(int val) { this.val = val; } }
      class JudgeSupport {
        static ListNode buildList(int[] values) {
          ListNode dummy = new ListNode(0), tail = dummy;
          for (int value : values) { tail.next = new ListNode(value); tail = tail.next; }
          return dummy.next;
        }
        static TreeNode buildTree(Integer[] values) {
          if (values.length == 0 || values[0] == null) return null;
          TreeNode root = new TreeNode(values[0]); Queue<TreeNode> queue = new LinkedList<>();
          queue.add(root); int index = 1;
          while (!queue.isEmpty() && index < values.length) {
            TreeNode node = queue.remove();
            if (index < values.length && values[index] != null) {
              node.left = new TreeNode(values[index]); queue.add(node.left);
            }
            index++;
            if (index < values.length && values[index] != null) {
              node.right = new TreeNode(values[index]); queue.add(node.right);
            }
            index++;
          }
          return root;
        }
        static boolean same(Object actual, Object expected, String type, String mode) {
          if ("INT_ARRAY".equals(type)) return Arrays.equals((int[]) actual, (int[]) expected);
          if ("INT_MATRIX".equals(type)) return Arrays.deepEquals((int[][]) actual, (int[][]) expected);
          if ("LIST_NODE".equals(type)) return listValues((ListNode) actual).equals(listValues((ListNode) expected));
          if ("TREE_NODE".equals(type)) return treeValues((TreeNode) actual).equals(treeValues((TreeNode) expected));
          if ("LIST_LIST_INT".equals(type) && !"ORDERED".equals(mode)) {
            return nestedValues(actual, mode).equals(nestedValues(expected, mode));
          }
          return Objects.equals(actual, expected);
        }
        static List<Integer> listValues(ListNode node) {
          List<Integer> values = new ArrayList<>(); int guard = 0;
          while (node != null && guard++ < 10000) { values.add(node.val); node = node.next; }
          return values;
        }
        static List<Integer> treeValues(TreeNode root) {
          if (root == null) return List.of();
          List<Integer> values = new ArrayList<>(); List<TreeNode> level = new ArrayList<>(); level.add(root);
          while (!level.isEmpty()) {
            List<TreeNode> next = new ArrayList<>(); boolean hasChild = false;
            for (TreeNode node : level) {
              if (node == null) { values.add(null); next.add(null); next.add(null); }
              else { values.add(node.val); next.add(node.left); next.add(node.right);
                if (node.left != null || node.right != null) hasChild = true; }
            }
            if (!hasChild) break; level = next;
          }
          int end = values.size(); while (end > 0 && values.get(end - 1) == null) end--;
          return new ArrayList<>(values.subList(0, end));
        }
        static List<String> nestedValues(Object value, String mode) {
          List<String> rows = new ArrayList<>();
          for (Object rowValue : (List<?>) value) {
            List<Integer> row = new ArrayList<>((List<Integer>) rowValue);
            if ("UNORDERED_ROWS_AND_VALUES".equals(mode)) Collections.sort(row);
            rows.add(row.toString());
          }
          Collections.sort(rows); return rows;
        }
      }
      """;

  private static final String PYTHON_SUPPORT = """
      import contextlib
      import io
      from collections import deque
      class ListNode:
          def __init__(self, val=0, next=None):
              self.val, self.next = val, next
      class TreeNode:
          def __init__(self, val=0, left=None, right=None):
              self.val, self.left, self.right = val, left, right
      def _build_list(values):
          dummy = ListNode()
          tail = dummy
          for value in values:
              tail.next = ListNode(value)
              tail = tail.next
          return dummy.next
      def _build_tree(values):
          if not values or values[0] is None:
              return None
          root = TreeNode(values[0])
          queue, index = deque([root]), 1
          while queue and index < len(values):
              node = queue.popleft()
              if index < len(values) and values[index] is not None:
                  node.left = TreeNode(values[index]); queue.append(node.left)
              index += 1
              if index < len(values) and values[index] is not None:
                  node.right = TreeNode(values[index]); queue.append(node.right)
              index += 1
          return root
      def _list_values(node):
          values, guard = [], 0
          while node is not None and guard < 10000:
              values.append(node.val); node = node.next; guard += 1
          return values
      def _tree_values(root):
          if root is None:
              return []
          values, queue = [], deque([root])
          while queue:
              node = queue.popleft()
              if node is None:
                  values.append(None)
              else:
                  values.append(node.val); queue.append(node.left); queue.append(node.right)
          while values and values[-1] is None:
              values.pop()
          return values
      def _same(actual, expected, value_type, mode):
          if value_type == "LIST_NODE":
              return _list_values(actual) == _list_values(expected)
          if value_type == "TREE_NODE":
              return _tree_values(actual) == _tree_values(expected)
          if value_type == "LIST_LIST_INT" and mode != "ORDERED":
              def normalize(rows):
                  result = []
                  for row in rows:
                      copy = list(row)
                      if mode == "UNORDERED_ROWS_AND_VALUES":
                          copy.sort()
                      result.append(tuple(copy))
                  return sorted(result)
              return normalize(actual) == normalize(expected)
          return actual == expected
      """;
}
