import {ChangeEvent, DragEvent, MouseEvent, useCallback, useState} from 'react';
import {AlertCircle, FileText, Info, Upload, X} from 'lucide-react';
import LoadingButtonContent from './LoadingButtonContent';
import {formatFileSize} from '../utils/format';

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
  const isEmbedded = variant === 'embedded';

  const applyFiles = useCallback((files: FileList | File[]) => {
    const list = Array.from(files);
    if (list.length === 0) return;
    if (multiple) {
      setSelectedFiles(list);
      onFileSelect?.(list[0]);
    } else {
      setSelectedFile(list[0]);
      onFileSelect?.(list[0]);
    }
  }, [multiple, onFileSelect]);

  const handleDragOver = useCallback((e: DragEvent) => {
    e.preventDefault();
    setDragOver(true);
  }, []);

  const handleDragLeave = useCallback((e: DragEvent) => {
    e.preventDefault();
    setDragOver(false);
  }, []);

  const handleDrop = useCallback((e: DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    if (e.dataTransfer.files.length > 0) {
      applyFiles(e.dataTransfer.files);
    }
  }, [applyFiles]);

  const handleFileChange = useCallback((e: ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (files && files.length > 0) {
      applyFiles(files);
    }
    e.target.value = '';
  }, [applyFiles]);

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
    <div className={isEmbedded ? '' : 'max-w-3xl mx-auto'}>
      {!isEmbedded && (
        <div className="text-center mb-8 pt-4">
          <h1 className="text-3xl font-display font-semibold text-stone-900 dark:text-stone-50 tracking-tight">
            {title}
          </h1>
          <p className="mt-2 text-stone-500 dark:text-stone-400">{subtitle}</p>
        </div>
      )}

      {isEmbedded && (
        <div className="mb-4">
          <h2 className="text-base font-semibold text-stone-900 dark:text-stone-100">{title}</h2>
          <p className="mt-1 text-sm text-stone-500 dark:text-stone-400">{subtitle}</p>
        </div>
      )}

      <div
        className={`dropzone relative p-8 md:p-10 cursor-pointer ${dragOver ? 'dropzone-active' : ''} ${isEmbedded ? 'surface-card' : ''}`}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onClick={() => document.getElementById(inputId)?.click()}
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

        {!hasSelection ? (
          <div className="text-center py-4">
            <div className={`mx-auto mb-4 w-14 h-14 rounded-2xl flex items-center justify-center ${
              dragOver ? 'bg-primary-100 text-primary-600 dark:bg-primary-900/40' : 'bg-stone-100 text-stone-400 dark:bg-stone-800'
            }`}>
              <Upload className="w-7 h-7" />
            </div>
            <p className="text-base font-medium text-stone-800 dark:text-stone-200">
              {multiple ? '拖拽多个文件到此处' : '拖拽文件到此处'}
            </p>
            <p className="mt-1 text-sm text-stone-500 dark:text-stone-400">
              {formatHint} · {maxSizeHint}
            </p>
            <button
              type="button"
              className="mt-5 btn-secondary px-5 py-2.5 rounded-lg text-sm font-medium"
              onClick={(e) => {
                e.preventDefault();
                e.stopPropagation();
                document.getElementById(inputId)?.click();
              }}
            >
              {multiple ? '选择多个文件' : selectButtonText}
            </button>
          </div>
        ) : (
          <div className="space-y-3" onClick={(e) => e.stopPropagation()}>
            {multiple ? (
              <>
                {selectedFiles.map((file) => (
                  <div
                    key={`${file.name}-${file.size}`}
                    className="flex items-center gap-3 rounded-xl bg-white dark:bg-stone-900/60 border border-stone-200/80 dark:border-stone-700 px-4 py-3"
                  >
                    <FileText className="w-5 h-5 text-primary-600 shrink-0" />
                    <div className="min-w-0 flex-1">
                      <p className="text-sm font-medium text-stone-900 dark:text-stone-100 truncate">{file.name}</p>
                      <p className="text-xs text-stone-500">{formatFileSize(file.size)}</p>
                    </div>
                    <button
                      type="button"
                      className="p-1.5 rounded-lg text-stone-400 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-950/30"
                      onClick={() => setSelectedFiles((prev) => prev.filter((f) => f !== file))}
                    >
                      <X className="w-4 h-4" />
                    </button>
                  </div>
                ))}
                <p className="text-center text-xs text-stone-500">已选 {selectedFiles.length} 个文件</p>
              </>
            ) : selectedFile && (
              <div className="flex items-center gap-3 rounded-xl bg-white dark:bg-stone-900/60 border border-stone-200/80 dark:border-stone-700 px-4 py-3 max-w-md mx-auto">
                <FileText className="w-5 h-5 text-primary-600 shrink-0" />
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium text-stone-900 dark:text-stone-100 truncate">{selectedFile.name}</p>
                  <p className="text-xs text-stone-500">{formatFileSize(selectedFile.size)}</p>
                </div>
                <button
                  type="button"
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
        <div className="mt-4 flex items-center gap-2 rounded-xl border border-red-200 dark:border-red-900/50 bg-red-50 dark:bg-red-950/30 px-4 py-3 text-sm text-red-600 dark:text-red-400">
          <AlertCircle className="w-4 h-4 shrink-0" />
          {error}
        </div>
      )}

      {notice && (
        <div className="mt-4 flex items-center gap-2 rounded-xl border border-amber-200 dark:border-amber-900/40 bg-amber-50 dark:bg-amber-950/20 px-4 py-3 text-sm text-amber-800 dark:text-amber-200">
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
