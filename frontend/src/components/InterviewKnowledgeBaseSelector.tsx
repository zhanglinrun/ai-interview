import { Database } from 'lucide-react';
import type { KnowledgeBaseItem } from '../api/knowledgebase';

interface InterviewKnowledgeBaseSelectorProps {
  knowledgeBases: KnowledgeBaseItem[];
  loading: boolean;
  selectedIds: number[];
  onToggle: (id: number) => void;
}

export default function InterviewKnowledgeBaseSelector({
  knowledgeBases,
  loading,
  selectedIds,
  onToggle,
}: InterviewKnowledgeBaseSelectorProps) {
  return (
    <div className="bg-gradient-to-br from-emerald-50/80 to-teal-50/80 dark:from-emerald-900/20 dark:to-teal-900/10 rounded-xl p-4 border border-emerald-100 dark:border-emerald-800/30">
      <div className="flex items-center gap-3 mb-3">
        <Database className="w-5 h-5 text-emerald-600 dark:text-emerald-400" />
        <div>
          <p className="font-semibold text-sm text-emerald-900 dark:text-emerald-100">
            关联知识库（可选）
          </p>
          <p className="text-xs text-emerald-700/80 dark:text-emerald-300/80 mt-0.5">
            选中后，出题前会先检索知识库资料，围绕资料要点出题
          </p>
        </div>
      </div>

      {loading ? (
        <p className="text-sm text-slate-500 dark:text-slate-400">加载知识库...</p>
      ) : knowledgeBases.length === 0 ? (
        <p className="text-sm text-slate-500 dark:text-slate-400">
          暂无已向量化完成的知识库，请先在知识库管理页上传并向量化。
        </p>
      ) : (
        <div className="max-h-40 overflow-y-auto space-y-2 pr-1">
          {knowledgeBases.map(kb => {
            const checked = selectedIds.includes(kb.id);
            return (
              <label
                key={kb.id}
                className={`flex items-start gap-3 p-2.5 rounded-lg border cursor-pointer transition-colors
                  ${checked
                    ? 'border-emerald-300 dark:border-emerald-600 bg-white/80 dark:bg-slate-800/80'
                    : 'border-transparent bg-white/50 dark:bg-slate-800/40 hover:bg-white/80 dark:hover:bg-slate-800/60'
                  }`}
              >
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => onToggle(kb.id)}
                  className="mt-1 rounded border-slate-300 text-emerald-600 focus:ring-emerald-500"
                />
                <span className="min-w-0">
                  <span className="block text-sm font-medium text-slate-800 dark:text-slate-100 truncate">
                    {kb.name}
                  </span>
                  {kb.category && (
                    <span className="block text-xs text-slate-500 dark:text-slate-400 mt-0.5">
                      {kb.category}
                    </span>
                  )}
                </span>
              </label>
            );
          })}
        </div>
      )}
    </div>
  );
}
