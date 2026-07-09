import { useCallback, useEffect, useMemo, useState } from 'react';
import { Network, RotateCcw, Search } from 'lucide-react';
import PageHeader from '../components/ui/PageHeader';
import { EmptyState, LoadingState } from '../components/PageState';
import { getErrorMessage } from '../api/request';
import { graphApi, type GraphNode, type GraphView } from '../api/graph';

const WIDTH = 900;
const HEIGHT = 560;
const CX = WIDTH / 2;
const CY = HEIGHT / 2;

const TYPE_COLOR: Record<string, string> = {
  Skill: '#6366f1',
  Concept: '#10b981',
  Document: '#f59e0b',
};
const TYPE_LABEL: Record<string, string> = {
  Skill: '技能方向',
  Concept: '知识概念',
  Document: '你的文档',
};
const nodeColor = (type: string) => TYPE_COLOR[type] ?? '#64748b';

interface Positioned extends GraphNode {
  x: number;
  y: number;
}

/**
 * 无依赖 SVG 布局：聚焦某节点时中心 + 一圈邻居；否则技能内圈、其余外圈。
 */
function layout(view: GraphView, focusId: string | null): Positioned[] {
  const { nodes } = view;
  if (nodes.length === 0) return [];

  if (focusId && nodes.some(n => n.id === focusId)) {
    const others = nodes.filter(n => n.id !== focusId);
    const radius = Math.min(WIDTH, HEIGHT) * 0.36;
    return nodes.map(n => {
      if (n.id === focusId) return { ...n, x: CX, y: CY };
      const idx = others.findIndex(o => o.id === n.id);
      const angle = (idx / Math.max(1, others.length)) * Math.PI * 2 - Math.PI / 2;
      return { ...n, x: CX + radius * Math.cos(angle), y: CY + radius * Math.sin(angle) };
    });
  }

  const skills = nodes.filter(n => n.type === 'Skill');
  const rest = nodes.filter(n => n.type !== 'Skill');
  const place = (list: GraphNode[], radius: number): Positioned[] =>
    list.map((n, i) => {
      const angle = (i / Math.max(1, list.length)) * Math.PI * 2 - Math.PI / 2;
      return { ...n, x: CX + radius * Math.cos(angle), y: CY + radius * Math.sin(angle) };
    });
  const inner = Math.min(WIDTH, HEIGHT) * (skills.length > 0 ? 0.2 : 0);
  const outer = Math.min(WIDTH, HEIGHT) * 0.42;
  return [...place(skills, inner), ...place(rest, outer)];
}

export default function KnowledgeGraphPage() {
  const [view, setView] = useState<GraphView>({ nodes: [], edges: [] });
  const [focusId, setFocusId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchInput, setSearchInput] = useState('');

  const loadOverview = useCallback(async () => {
    setLoading(true);
    setError(null);
    setFocusId(null);
    try {
      setView(await graphApi.overview());
    } catch (err) {
      setError(getErrorMessage(err, '图谱加载失败'));
    } finally {
      setLoading(false);
    }
  }, []);

  const loadNeighbors = useCallback(async (name: string) => {
    if (!name.trim()) return;
    setLoading(true);
    setError(null);
    try {
      const data = await graphApi.neighbors(name.trim());
      setView(data);
      const center = data.nodes.find(n => n.label === name.trim());
      setFocusId(center ? center.id : null);
    } catch (err) {
      setError(getErrorMessage(err, '子图加载失败'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadOverview();
  }, [loadOverview]);

  const positioned = useMemo(() => layout(view, focusId), [view, focusId]);
  const posById = useMemo(() => {
    const map = new Map<string, Positioned>();
    positioned.forEach(p => map.set(p.id, p));
    return map;
  }, [positioned]);

  const skillCount = view.nodes.filter(n => n.type === 'Skill').length;
  const conceptCount = view.nodes.filter(n => n.type !== 'Skill').length;

  const onNodeClick = (node: GraphNode) => loadNeighbors(node.label);

  return (
    <div className="max-w-6xl mx-auto px-4 py-6 space-y-5">
      <PageHeader
        eyebrow="知识图谱"
        title="技能 / 知识图谱"
        description="把面试方向、知识点和你的文档连成一张网，看清「这个方向要考哪些点、我的资料覆盖了哪些」"
      />

      {/* 这张图是什么 / 怎么用 */}
      <div className="surface-card p-5 grid gap-4 md:grid-cols-3 text-sm">
        <div>
          <p className="font-semibold text-stone-800 dark:text-stone-100 mb-1">这是什么</p>
          <p className="text-stone-500 dark:text-stone-400 leading-relaxed text-[13px]">
            紫色是面试<span className="font-medium text-indigo-500">技能方向</span>（如 Redis、Java 后端），
            绿色是它覆盖的<span className="font-medium text-emerald-500">知识概念</span>，
            橙色是你上传的<span className="font-medium text-amber-500">文档</span>。连线表示「方向考察概念 / 概念来自文档」。
          </p>
        </div>
        <div>
          <p className="font-semibold text-stone-800 dark:text-stone-100 mb-1">数据从哪来</p>
          <p className="text-stone-500 dark:text-stone-400 leading-relaxed text-[13px]">
            技能方向是系统预置的；你每上传并向量化一份文档，系统会自动从文档标题结构里抽取概念挂到图上，不需要手工维护。
          </p>
        </div>
        <div>
          <p className="font-semibold text-stone-800 dark:text-stone-100 mb-1">有什么用</p>
          <p className="text-stone-500 dark:text-stone-400 leading-relaxed text-[13px]">
            点任意节点（或搜索「Redis」）下钻一跳子图，能看到一个方向关联的知识点分布；
            问答助手遇到「XX 方向要考什么」这类问题时也会查这张图回答。
          </p>
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <form
          onSubmit={e => {
            e.preventDefault();
            loadNeighbors(searchInput);
          }}
          className="flex items-center gap-2"
        >
          <div className="relative">
            <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
            <input
              value={searchInput}
              onChange={e => setSearchInput(e.target.value)}
              placeholder="输入技能/概念名下钻，如 Redis"
              className="pl-9 pr-3 py-2 w-72 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-primary-500/50"
            />
          </div>
          <button type="submit" className="px-4 py-2 rounded-lg btn-primary text-sm font-medium">
            下钻
          </button>
        </form>
        <button onClick={loadOverview} className="px-4 py-2 rounded-lg btn-secondary text-sm font-medium inline-flex items-center gap-2">
          <RotateCcw className="w-4 h-4" /> 概览
        </button>
        <div className="flex items-center gap-4 text-xs text-slate-500 ml-auto">
          {Object.entries(TYPE_COLOR).map(([type, color]) => (
            <span key={type} className="inline-flex items-center gap-1.5">
              <span className="w-2.5 h-2.5 rounded-full" style={{ background: color }} />
              {TYPE_LABEL[type] ?? type}
            </span>
          ))}
          <span>节点 {view.nodes.length}（技能 {skillCount} · 概念/文档 {conceptCount}） · 关系 {view.edges.length}</span>
        </div>
      </div>

      {error && (
        <div className="rounded-lg border border-red-200 dark:border-red-800 bg-red-50 dark:bg-red-900/20 px-4 py-3 text-sm text-red-700 dark:text-red-300">
          {error}
        </div>
      )}

      <div className="rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900/40 overflow-hidden">
        {loading ? (
          <LoadingState className="flex items-center justify-center h-[560px]" spinnerClassName="w-8 h-8 text-primary-500 animate-spin" />
        ) : view.nodes.length === 0 ? (
          <EmptyState
            icon={Network}
            title="图谱为空"
            description="Neo4j 未启用或图谱尚未同步。开启 app.ai.rag.graph.enabled 并上传/向量化文档后再来查看。"
          />
        ) : (
          <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} className="w-full h-auto" role="img">
            {view.edges.map((e, i) => {
              const s = posById.get(e.source);
              const t = posById.get(e.target);
              if (!s || !t) return null;
              return (
                <line
                  key={i}
                  x1={s.x}
                  y1={s.y}
                  x2={t.x}
                  y2={t.y}
                  stroke="currentColor"
                  className="text-slate-300 dark:text-slate-700"
                  strokeWidth={1}
                />
              );
            })}
            {positioned.map(n => (
              <g key={n.id} transform={`translate(${n.x}, ${n.y})`} className="cursor-pointer" onClick={() => onNodeClick(n)}>
                <circle
                  r={n.id === focusId ? 11 : n.type === 'Skill' ? 9 : 6}
                  fill={nodeColor(n.type)}
                  stroke={n.id === focusId ? '#0f172a' : 'white'}
                  strokeWidth={n.id === focusId ? 2 : 1}
                />
                <text
                  x={0}
                  y={n.type === 'Skill' || n.id === focusId ? -14 : 12}
                  textAnchor="middle"
                  className="fill-slate-600 dark:fill-slate-300"
                  style={{ fontSize: n.type === 'Skill' || n.id === focusId ? 12 : 10 }}
                >
                  {n.label.length > 12 ? `${n.label.slice(0, 12)}…` : n.label}
                </text>
              </g>
            ))}
          </svg>
        )}
      </div>
    </div>
  );
}
