const ALGORITHM_TAG_LABELS: Record<string, string> = {
  ARRAY: '数组',
  BACKTRACKING: '回溯',
  BFS: '广度优先',
  BINARY_SEARCH: '二分查找',
  BIT_MANIPULATION: '位运算',
  BST: '二叉搜索树',
  COMBINATORICS: '组合数学',
  DESIGN: '设计',
  DFS: '深度优先',
  DIVIDE_AND_CONQUER: '分治',
  DYNAMIC_PROGRAMMING: '动态规划',
  GRAPH: '图',
  GREEDY: '贪心',
  HASH_TABLE: '哈希表',
  HEAP: '堆',
  LINKED_LIST: '链表',
  MATH: '数学',
  MATRIX: '矩阵',
  MONOTONIC_STACK: '单调栈',
  PREFIX_SUM: '前缀和',
  QUEUE: '队列',
  RECURSION: '递归',
  SIMULATION: '模拟',
  SLIDING_WINDOW: '滑动窗口',
  SORTING: '排序',
  STACK: '栈',
  STRING: '字符串',
  TOPOLOGICAL_SORT: '拓扑排序',
  TREE: '树',
  TRIE: '字典树',
  TWO_POINTERS: '双指针',
};

const CAPABILITY_ATOM_LABELS: Record<string, string> = {
  JAVA_LANGUAGE_FOUNDATION: 'Java 语言与并发基础',
  SPRING_APPLICATION: 'Spring 应用开发',
  DATABASE_TRANSACTION: '数据库与事务',
  CACHE_DISTRIBUTED: '缓存与分布式协调',
  MESSAGE_RELIABILITY: '消息可靠性',
  BACKEND_SYSTEM_DESIGN: '后端系统设计',
  PROJECT_TROUBLESHOOTING: '项目深挖与故障定位',
  RAG_DOCUMENT_PIPELINE: 'RAG 文档处理链路',
  RAG_RETRIEVAL: '检索与证据编排',
  RAG_EVALUATION: 'RAG 评测',
  AGENT_ORCHESTRATION: 'Agent 编排',
  LLM_APPLICATION_ENGINEERING: 'LLM 应用工程',
  AI_APPLICATION_RELIABILITY: 'AI 应用可靠性',
  ALGORITHM_PROBLEM_SOLVING: '算法与问题求解',
};

export function getAlgorithmTagLabel(tag: string): string {
  return ALGORITHM_TAG_LABELS[tag] ?? tag;
}

export function getCapabilityDisplayName(atomId: string, capabilityName?: string | null): string {
  const normalizedName = capabilityName?.trim();
  if (normalizedName && normalizedName !== atomId && !/^[A-Z0-9_]+$/.test(normalizedName)) {
    return normalizedName;
  }
  return CAPABILITY_ATOM_LABELS[atomId] ?? normalizedName ?? atomId;
}
