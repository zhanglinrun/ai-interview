import type { SkillDTO } from '../api/skill';
import { CUSTOM_SKILL_ID } from '../hooks/useInterviewConfig';
import LoadingButtonContent from './LoadingButtonContent';
import { getSkillIcon } from '../utils/skillIcons';

interface InterviewSkillSelectorProps {
  skills: SkillDTO[];
  loading: boolean;
  value: string;
  onChange: (skillId: string) => void;
  isCustomSkill: boolean;
  compact?: boolean;
}

export default function InterviewSkillSelector({
  skills,
  loading,
  value,
  onChange,
  isCustomSkill,
  compact = false,
}: InterviewSkillSelectorProps) {
  const gridClassName = compact
    ? 'grid grid-cols-2 gap-2'
    : 'grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-2';
  const itemGapClassName = compact ? 'gap-3' : 'gap-2.5';
  const iconBoxClassName = compact ? 'w-9 h-9 text-base' : 'w-8 h-8 text-sm';
  const iconClassName = compact ? 'w-5 h-5' : 'w-4 h-4';
  const fallbackClassName = compact ? 'text-base' : 'text-sm';

  return (
    <div>
      <label className="flex items-center gap-2 mb-3 text-sm font-semibold text-slate-700 dark:text-slate-200">
        面试方向
      </label>
      {loading ? (
        <div className="flex items-center gap-2 py-4 text-slate-400">
          <LoadingButtonContent
            loading
            loadingText="加载中..."
            className="inline-flex items-center gap-2 text-sm"
          >
            <span className="text-sm">加载中...</span>
          </LoadingButtonContent>
        </div>
      ) : (
        <div className={gridClassName}>
          {skills.map(skill => {
            const selected = value === skill.id;
            const IconComponent = getSkillIcon(skill.id);
            const fallbackEmoji = skill.display?.icon || '📋';
            return (
              <button
                key={skill.id}
                onClick={() => onChange(skill.id)}
                className={`flex items-center ${itemGapClassName} p-3 rounded-xl border-2 transition-all duration-200 text-left
                  ${selected
                    ? 'border-primary-500 bg-primary-50/80 dark:bg-primary-900/20'
                    : 'border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 hover:border-slate-300 dark:hover:border-slate-600'
                  }`}
              >
                <div className={`${iconBoxClassName} rounded-lg flex items-center justify-center flex-shrink-0 ${
                  selected ? skill.display?.iconBg || 'bg-primary-100 dark:bg-primary-900/50' : 'bg-slate-100 dark:bg-slate-700'
                }`}>
                  {IconComponent
                    ? <IconComponent className={`${iconClassName} ${selected ? (skill.display?.iconColor || 'text-primary-600') : 'text-slate-500 dark:text-slate-400'}`} />
                    : <span className={selected ? (skill.display?.iconColor || 'text-primary-600') : ''}>{fallbackEmoji}</span>
                  }
                </div>
                <div className="flex-1 min-w-0">
                  <span className={`text-xs font-medium block truncate ${selected ? 'text-primary-700 dark:text-primary-300' : 'text-slate-700 dark:text-slate-300'}`}>
                    {skill.name}
                  </span>
                  {compact && (
                    <span className="text-[10px] text-slate-400 truncate block">
                      {skill.description}
                    </span>
                  )}
                </div>
              </button>
            );
          })}

          <button
            onClick={() => onChange(CUSTOM_SKILL_ID)}
            className={`flex items-center ${itemGapClassName} p-3 rounded-xl border-2 border-dashed transition-all duration-200 text-left
              ${isCustomSkill
                ? 'border-primary-500 bg-primary-50/80 dark:bg-primary-900/20'
                : 'border-slate-200 dark:border-slate-700 hover:border-primary-300 dark:hover:border-primary-600'
              }`}
          >
            <div className={`${iconBoxClassName} rounded-lg flex items-center justify-center flex-shrink-0 ${
              isCustomSkill ? 'bg-primary-100 dark:bg-primary-900/50' : 'bg-slate-100 dark:bg-slate-700'
            }`}>
              {(() => {
                const CustomIcon = getSkillIcon(CUSTOM_SKILL_ID);
                return CustomIcon
                  ? <CustomIcon className={`${iconClassName} ${isCustomSkill ? 'text-primary-600 dark:text-primary-400' : 'text-slate-500 dark:text-slate-400'}`} />
                  : <span className={fallbackClassName}>✨</span>;
              })()}
            </div>
            <div className="flex-1 min-w-0">
              <span className={`text-xs font-medium ${compact ? 'block' : ''} ${isCustomSkill ? 'text-primary-700 dark:text-primary-300' : 'text-slate-500 dark:text-slate-400'}`}>
                自定义 JD
              </span>
            </div>
          </button>
        </div>
      )}
    </div>
  );
}
