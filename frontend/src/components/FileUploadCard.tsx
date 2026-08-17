import {ChangeEvent, DragEvent, KeyboardEvent, MouseEvent, useCallback, useState} from 'react';
import {AlertCircle, FileText, Info, Upload, X} from 'lucide-react';
import LoadingButtonContent from './LoadingButtonContent';
import {collectDroppedFiles, matchesAccept, mergeUniqueFiles} from '../utils/collectDroppedFiles';
import {formatFileSize} from '../utils/format';

const VISIBLE_FILE_PREVIEW = 8;

export interface FileUploadCardProps {
  title: string;
  subtitle: string;
  accept: string;
  formatHint: string;
  maxSizeHint: string;
  uploading?: boolean;
  uploadButtonText?: string;
  selectButtonText?: string;
  showNameInput?: boolean;
  namePlaceholder?: string;
  nameLabel?: string;
  error?: string;
  notice?: string;
  onFileSelect?: (file: File) => void;
  onUpload: (file: File, name?: string) => void;
  onBatchUpload?: (files: File[]) => void;
  multiple?: boolean;
  onBack?: () => void;
  inputId?: string;
  /** hero=独立大标题页；embedded=嵌入侧栏布局 */
  variant?: 'hero' | 'embedded';
}

export default function FileUploadCard({
  title,
  subtitle,
  accept,
  formatHint,
  maxSizeHint,
  uploading = false,
  uploadButtonText = '开始上传',
  selectButtonText = '选择文件',
  showNameInput = false,
  namePlaceholder = '留空则使用文件名',
  nameLabel = '名称（可选）',
  error,
  notice,
  onFileSelect,
  onUpload,
  onBatchUpload,
  multiple = false,
  onBack,
  inputId = 'file-upload-input',
  variant = 'hero',
}: FileUploadCardProps) {
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);
  const [dragOver, setDragOver] = useState(false);
  const [name, setName] = useState('');
  const [expandedList, setExpandedList] = useState(false);
  const isEmbedded = variant === 'embedded';
  const visibleFiles = multiple && !expandedList && selectedFiles.length > VISIBLE_FILE_PREVIEW
    ? selectedFiles.slice(0, VISIBLE_FILE_PREVIEW)
    : selectedFiles;

  const applyFiles = useCallback((files: FileList | File[]) => {
    const list = Array.from(files).filter((file) => matchesAccept(file.name, accept));
    if (list.length === 0) return;
    if (multiple) {
      setSelectedFiles((previous) => mergeUniqueFiles(previous, list));
      onFileSelect?.(list[0]);
    } else {
      setSelectedFile(list[0]);
      onFileSelect?.(list[0]);
    }
  }, [accept, multiple, onFileSelect]);

  const handleDragOver = useCallback((e: DragEvent) => {
    e.preventDefault();
    setDragOver(true);
  }, []);

  const handleDragLeave = useCallback((e: DragEvent) => {
    e.preventDefault();
    setDragOver(false);
  }, []);

  const handleDrop = useCallback(async (e: DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const dropped = await collectDroppedFiles(e.dataTransfer, accept);
    if (dropped.length > 0) {
      applyFiles(dropped);
    }
  }, [accept, applyFiles]);

  const handleFileChange = useCallback((e: ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (files && files.length > 0) {
      applyFiles(files);
    }
    e.target.value = '';
  }, [applyFiles]);

  const handleKeyDown = useCallback((event: KeyboardEvent<HTMLDivElement>) => {
    if (event.target !== event.currentTarget || uploading) return;
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      document.getElementById(inputId)?.click();
    }
  }, [inputId, uploading]);

  const handleUpload = (event: MouseEvent<HTMLButtonElement>) => {
    event.preventDefault();
    event.stopPropagation();
    if (multiple) {
      if (selectedFiles.length === 0 || !onBatchUpload) return;
      onBatchUpload(selectedFiles);
      return;
    }
    if (!selectedFile) return;
    onUpload(selectedFile, name.trim() || undefined);
  };

  const hasSelection = multiple ? selectedFiles.length > 0 : !!selectedFile;

  return (
    <div className={isEmbedded ? '' : 'max-w-2xl mx-auto'}>
      {!isEmbedded && (
        <div className="mb-5">
          <h1 className="text-2xl font-display font-semibold text-stone-900 dark:text-stone-50 tracking-tight">
            {title}
          </h1>
          <p className="mt-1.5 text-sm text-stone-500 dark:text-stone-400">{subtitle}</p>
        </div>
      )}

      {isEmbedded && (
        <div className="mb-4">
          <h2 className="text-base font-semibold text-stone-900 dark:text-stone-100">{title}</h2>
          <p className="mt-1 text-sm text-stone-500 dark:text-stone-400">{subtitle}</p>
        </div>
      )}

      <div
        className={`dropzone relative p-6 md:p-8 cursor-pointer ${dragOver ? 'dropzone-active' : ''} ${isEmbedded ? 'surface-card' : ''}`}
        role="button"
        tabIndex={uploading ? -1 : 0}
        aria-disabled={uploading}
        aria-label={multiple ? '选择多个文件上传' : '选择文件上传'}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onClick={() => document.getElementById(inputId)?.click()}
        onKeyDown={handleKeyDown}
      >
        <input
          type="file"
          id={inputId}
          className="hidden"
          accept={accept}
          multiple={multiple}
          onChange={handleFileChange}
          disabled={uploading}
        />
        {multiple && (
          <input
            type="file"
            id={`${inputId}-dir`}
            className="hidden"
            multiple
            // @ts-expect-error non-standard folder picker
            webkitdirectory=""
            onChange={handleFileChange}
            disabled={uploading}
          />
        )}

        {!hasSelection ? (
          <div className="text-center py-3">
            <div className={`mx-auto mb-3 w-12 h-12 rounded-lg flex items-center justify-center ${
              dragOver ? 'bg-primary-100 text-primary-600 dark:bg-primary-900/40' : 'bg-stone-100 text-stone-400 dark:bg-stone-800'
            }`}>
              <Upload className="w-6 h-6" />
            </div>
            <p className="text-base font-medium text-stone-800 dark:text-stone-200">
              {multiple ? '拖拽文件或整个文件夹到此处' : '拖拽文件到此处'}
            </p>
            <p className="mt-1 text-sm text-stone-500 dark:text-stone-400">
              {formatHint} · {maxSizeHint}
            </p>
            <div className="mt-5 flex flex-wrap items-center justify-center gap-3">
              <button
                type="button"
                className="btn-secondary px-5 py-2.5 rounded-lg text-sm font-medium"
                onClick={(e) => {
                  e.preventDefault();
                  e.stopPropagation();
                  document.getElementById(inputId)?.click();
                }}
              >
                {multiple ? '选择多个文件' : selectButtonText}
              </button>
              {multiple && (
                <button
                  type="button"
                  className="btn-secondary px-5 py-2.5 rounded-lg text-sm font-medium"
                  onClick={(e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    document.getElementById(`${inputId}-dir`)?.click();
                  }}
                >
                  选择文件夹
                </button>
              )}
            </div>
          </div>
        ) : (
          <div className="space-y-3" onClick={(e) => e.stopPropagation()}>
            {multiple ? (
              <>
                <p className="text-center text-sm font-medium text-stone-700 dark:text-stone-200">
                  已选 {selectedFiles.length} 个文件，将全部上传
                </p>
                {visibleFiles.map((file) => (
                  <div
                    key={`${file.webkitRelativePath || file.name}-${file.size}-${file.lastModified}`}
                    className="flex items-center gap-3 rounded-lg bg-white dark:bg-stone-900/60 border border-stone-200/80 dark:border-stone-700 px-4 py-3"
                  >
                    <FileText className="w-5 h-5 text-primary-600 shrink-0" />
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-medium text-stone-900 dark:text-stone-100 truncate">{file.name}</p>
                      <p className="text-xs text-stone-500">{formatFileSize(file.size)}</p>
                    </div>
                    <button
                      type="button"
                      aria-label={`移除文件 ${file.name}`}
                      className="p-1.5 rounded-lg text-stone-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-950/30"
                      onClick={() => setSelectedFiles((prev) => prev.filter((f) => f !== file))}
                    >
                      <X className="w-4 h-4" />
                    </button>
                  </div>
                ))}
                {selectedFiles.length > VISIBLE_FILE_PREVIEW && (
                  <button
                    type="button"
                    className="w-full text-center text-xs text-primary-600 hover:underline"
                    onClick={() => setExpandedList((open) => !open)}
                  >
                    {expandedList
                      ? '收起列表'
                      : `其余 ${selectedFiles.length - VISIBLE_FILE_PREVIEW} 个文件已加入，点击查看全部`}
                  </button>
                )}
                <div className="flex justify-center gap-3 text-xs">
                  <button
                    type="button"
                    className="text-stone-500 hover:text-stone-700 dark:hover:text-stone-300"
                    onClick={() => document.getElementById(inputId)?.click()}
                  >
                    继续添加
                  </button>
                  <button
                    type="button"
                    className="text-stone-500 hover:text-red-500"
                    onClick={() => {
                      setSelectedFiles([]);
                      setExpandedList(false);
                    }}
                  >
                    清空
                  </button>
                </div>
              </>
            ) : selectedFile && (
              <div className="flex items-center gap-3 rounded-lg bg-white dark:bg-stone-900/60 border border-stone-200/80 dark:border-stone-700 px-4 py-3 max-w-md mx-auto">
                <FileText className="w-5 h-5 text-primary-600 shrink-0" />
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium text-stone-900 dark:text-stone-100 truncate">{selectedFile.name}</p>
                  <p className="text-xs text-stone-500">{formatFileSize(selectedFile.size)}</p>
                </div>
                <button
                  type="button"
                  aria-label={`移除文件 ${selectedFile.name}`}
                  className="p-1.5 rounded-lg text-stone-400 hover:text-red-500"
                  onClick={() => setSelectedFile(null)}
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
            )}
          </div>
        )}
      </div>

      {showNameInput && !multiple && selectedFile && (
        <div className="mt-4 surface-card p-4">
          <label className="block text-sm font-medium text-stone-700 dark:text-stone-300 mb-2">{nameLabel}</label>
          <input
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={namePlaceholder}
            className="dark-input w-full px-3 py-2.5 rounded-lg text-sm"
            disabled={uploading}
          />
        </div>
      )}

      {error && (
        <div className="mt-4 flex items-center gap-2 rounded-lg border border-red-200 dark:border-red-900/50 bg-red-50 dark:bg-red-950/30 px-4 py-3 text-sm text-red-600 dark:text-red-400">
          <AlertCircle className="w-4 h-4 shrink-0" />
          {error}
        </div>
      )}

      {notice && (
        <div className="mt-4 flex items-center gap-2 rounded-lg border border-amber-200 dark:border-amber-900/40 bg-amber-50 dark:bg-amber-950/20 px-4 py-3 text-sm text-amber-800 dark:text-amber-200">
          <Info className="w-4 h-4 shrink-0" />
          {notice}
        </div>
      )}

      <div className={`flex gap-3 ${isEmbedded ? 'mt-5' : 'mt-6 justify-center'}`}>
        {onBack && (
          <button type="button" onClick={onBack} className="btn-secondary px-5 py-2.5 rounded-lg text-sm font-medium">
            返回
          </button>
        )}
        {hasSelection && (
          <button
            type="button"
            onClick={handleUpload}
            disabled={uploading}
            className="btn-primary px-6 py-2.5 rounded-lg text-sm font-medium disabled:opacity-50 inline-flex items-center gap-2"
          >
            <LoadingButtonContent loading={uploading} loadingText="处理中...">
              {uploadButtonText}
            </LoadingButtonContent>
          </button>
        )}
      </div>
    </div>
  );
}
