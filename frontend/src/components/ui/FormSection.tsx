import type {ReactNode} from 'react';

interface FormSectionProps {
  title: string;
  description?: string;
  children: ReactNode;
  className?: string;
}

export default function FormSection({title, description, children, className = ''}: FormSectionProps) {
  return (
    <section className={`surface-card p-5 ${className}`}>
      <div className="mb-4">
        <h2 className="text-sm font-semibold text-stone-800 dark:text-stone-100">{title}</h2>
        {description && (
          <p className="mt-1 text-xs text-stone-500 dark:text-stone-400 leading-relaxed">{description}</p>
        )}
      </div>
      {children}
    </section>
  );
}
