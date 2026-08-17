import {
  ArrowUpRight,
  BookOpenCheck,
  Code2,
  Coffee,
  Database,
  Map,
  MessageSquareText,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { Link } from 'react-router-dom';
import PageHeader from '../components/ui/PageHeader';
import { LEARNING_RESOURCES } from '../constants/productLinks';

const RESOURCE_ICONS: Record<string, LucideIcon> = {
  'leetcode-cn': Code2,
  programmercarl: Map,
  'java-guide': Coffee,
  'mianzha-nixi': BookOpenCheck,
};

export default function CareerResourcesPage() {
  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader
        title="求职资源"
        description="常用的刷题和后端复习入口。学完后可带着 JD 和简历去做模拟面试。"
      />

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {LEARNING_RESOURCES.map((resource) => {
          const Icon = RESOURCE_ICONS[resource.id] ?? BookOpenCheck;
          return (
            <a
              key={resource.id}
              href={resource.url}
              target="_blank"
              rel="noopener noreferrer"
              className="group min-h-36 rounded-xl border border-stone-200 bg-white p-4 shadow-sm transition-[border-color,box-shadow] hover:border-primary-200 hover:shadow-md dark:border-stone-800 dark:bg-stone-900 dark:hover:border-primary-800"
            >
              <div className="flex items-start gap-3">
                <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-stone-100 text-stone-700 dark:bg-stone-800 dark:text-stone-200">
                  <Icon className="h-5 w-5" />
                </div>
                <div className="min-w-0 flex-1 pt-0.5">
                  <div className="flex items-center justify-between gap-2">
                    <h2 className="truncate text-[15px] font-semibold text-stone-900 dark:text-stone-50">{resource.name}</h2>
                    <ArrowUpRight className="h-4 w-4 shrink-0 text-stone-300 transition-colors group-hover:text-primary-600 dark:text-stone-600" />
                  </div>
                  <span className="mt-1.5 inline-flex rounded bg-primary-50 px-1.5 py-0.5 text-[11px] font-medium text-primary-700 dark:bg-primary-950/50 dark:text-primary-300">
                    {resource.badge}
                  </span>
                </div>
              </div>
              <p className="mt-3 line-clamp-2 text-sm leading-5 text-stone-500 dark:text-stone-400">{resource.description}</p>
            </a>
          );
        })}
      </div>

      <section className="mt-7">
        <h2 className="mb-3 text-base font-semibold text-stone-900 dark:text-stone-50">平台内练习</h2>
        <div className="grid max-w-4xl gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {[
            ['/interview', MessageSquareText, '模拟面试', '按 JD、简历和知识库做文字模拟面试并查看复盘'],
            ['/knowledgebase', Database, '资料管理', '上传和维护分域知识资料'],
            ['/knowledgebase/chat', MessageSquareText, 'RAG 问答', '用引用证据检查自己的理解'],
          ].map(([path, Icon, title, description]) => (
            <Link
              key={String(path)}
              to={String(path)}
              className="group flex items-center gap-3 rounded-xl border border-stone-200 bg-white p-3.5 shadow-sm transition hover:border-primary-200 hover:shadow-md dark:border-stone-800 dark:bg-stone-900 dark:hover:border-primary-800"
            >
              <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-primary-50 text-primary-700 dark:bg-primary-950/50 dark:text-primary-300">
                <Icon className="h-4.5 w-4.5" />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block text-sm font-semibold text-stone-900 dark:text-stone-50">{String(title)}</span>
                <span className="mt-0.5 block truncate text-xs text-stone-500 dark:text-stone-400">{String(description)}</span>
              </span>
              <ArrowUpRight className="h-4 w-4 shrink-0 text-stone-300 transition-colors group-hover:text-primary-600 dark:text-stone-600" />
            </Link>
          ))}
        </div>
      </section>
    </div>
  );
}
