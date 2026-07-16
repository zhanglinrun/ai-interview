import {BriefcaseBusiness, FileText} from 'lucide-react';

export default function InterviewTypeBadge({ jobInterview = false }: { jobInterview?: boolean }) {
  if (jobInterview) {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-primary-100 dark:bg-primary-900/30 text-primary-700 dark:text-primary-300 rounded-full text-xs font-medium">
        <BriefcaseBusiness className="w-3 h-3" />
        岗位实战
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400 rounded-full text-xs font-medium">
      <FileText className="w-3 h-3" />
      文字
    </span>
  );
}
