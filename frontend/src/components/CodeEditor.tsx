import Editor, { loader } from '@monaco-editor/react';
import 'monaco-editor/esm/vs/basic-languages/java/java.contribution.js';
import 'monaco-editor/esm/vs/basic-languages/python/python.contribution.js';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api.js';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker.js?worker';

loader.config({ monaco });

type MonacoGlobal = typeof globalThis & {
  MonacoEnvironment?: {
    getWorker: () => Worker;
  };
};

(globalThis as MonacoGlobal).MonacoEnvironment = {
  getWorker: () => new EditorWorker(),
};

interface CodeEditorProps {
  value: string;
  onChange: (value: string) => void;
  language: 'java' | 'python';
  ariaLabel: string;
  height?: number;
  readOnly?: boolean;
}

/**
 * 本地打包、按路由懒加载的 Monaco 编辑器。
 * 只启用 Java/Python 所需的基础编辑 Worker，不依赖 CDN，也不把编辑器资源加载到普通页面。
 */
export default function CodeEditor({
  value,
  onChange,
  language,
  ariaLabel,
  height = 460,
  readOnly = false,
}: CodeEditorProps) {
  return (
    <Editor
      height={height}
      language={language}
      value={value}
      theme="vs-dark"
      onChange={(nextValue) => onChange(nextValue ?? '')}
      loading={(
        <div
          className="flex items-center justify-center bg-[#0c1017] text-sm text-slate-400"
          style={{ height }}
        >
          正在加载代码编辑器…
        </div>
      )}
      options={{
        ariaLabel,
        automaticLayout: true,
        contextmenu: true,
        fontFamily: "'JetBrains Mono', 'Cascadia Code', Consolas, monospace",
        fontSize: 14,
        formatOnPaste: true,
        insertSpaces: true,
        lineNumbersMinChars: 3,
        minimap: { enabled: false },
        padding: { top: 16, bottom: 16 },
        readOnly,
        renderWhitespace: 'selection',
        scrollBeyondLastLine: false,
        smoothScrolling: true,
        tabSize: 4,
        wordWrap: 'off',
      }}
    />
  );
}
