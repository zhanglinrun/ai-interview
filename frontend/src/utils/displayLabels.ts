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

const SKILL_LABELS: Record<string, string> = {
  'java-backend': 'Java 后端',
  'ai-rag-agent': 'AI / RAG / Agent',
  custom: '自定义方向',
};

export function getSkillLabel(skillId: string | null | undefined): string {
  const normalized = skillId?.trim();
  if (!normalized) return '文字面试';
  return SKILL_LABELS[normalized] ?? normalized;
}

export function getCapabilityDisplayName(atomId: string, capabilityName?: string | null): string {
  const normalizedName = capabilityName?.trim();
  if (normalizedName && normalizedName !== atomId && !/^[A-Z0-9_]+$/.test(normalizedName)) {
    return normalizedName;
  }
  return CAPABILITY_ATOM_LABELS[atomId] ?? normalizedName ?? atomId;
}
