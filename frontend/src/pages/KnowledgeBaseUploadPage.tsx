import {useState} from 'react';
import {FileSpreadsheet, Globe, Lock} from 'lucide-react';
import {
  knowledgeBaseApi,
  type BatchUploadItemResult,
  type DocumentAccessScope,
  type UploadKnowledgeBaseResponse,
} from '../api/knowledgebase';
import { documentApi } from '../api/document';
import {getErrorMessage} from '../api/request';
import FileUploadCard from '../components/FileUploadCard';
import FormSection from '../components/ui/FormSection';
import OptionTile from '../components/ui/OptionTile';
import PageHeader from '../components/ui/PageHeader';
import SegmentedControl from '../components/ui/SegmentedControl';

interface KnowledgeBaseUploadPageProps {
  onUploadComplete: (result: UploadKnowledgeBaseResponse) => void;
  onBack: () => void;
}

type UploadMode = 'single' | 'batch';

export default function KnowledgeBaseUploadPage({ onUploadComplete, onBack }: KnowledgeBaseUploadPageProps) {
  const [uploadMode, setUploadMode] = useState<UploadMode>('single');
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [batchCategory, setBatchCategory] = useState('');
  const [batchResults, setBatchResults] = useState<BatchUploadItemResult[] | null>(null);
  const [accessibleBy, setAccessibleBy] = useState<DocumentAccessScope>('PRIVATE');
  const [expireDate, setExpireDate] = useState('');

  const handleUpload = async (file: File, name?: string) => {
    setUploading(true);
    setError('');
    setNotice('');

    try {
      const data = await documentApi.upload(file, name, undefined, accessibleBy, expireDate);
      setNotice(`已接收文档，生成 ${data.segmentCount} 个知识分段，正在异步向量化。`);
      onUploadComplete({
        knowledgeBase: {
          id: data.documentId,
          name: name || file.name,
          category: '',
          fileSize: file.size,
          contentLength: 0,
          docStatus: data.status as UploadKnowledgeBaseResponse['knowledgeBase']['docStatus'],
        },
        storage: { fileKey: '', fileUrl: '' },
        duplicate: false,
      });
    } catch (err: unknown) {
      setError(getErrorMessage(err, '上传失败，请重试'));
      setUploading(false);
    }
  };

  const handleBatchUpload = async (files: File[]) => {
    if (files.length === 0) return;
    setUploading(true);
    setError('');
    setNotice('');
    setBatchResults(null);
    try {
      const result = await knowledgeBaseApi.uploadKnowledgeBaseBatch(
        files,
        batchCategory.trim() || undefined,
        accessibleBy,
      );
      setBatchResults(result.items);
      if (result.success > 0) {
        // 批量接口不返回各文档 docId：上传成功后【后台异步】拉取 CONVERTED 知识库逐个触发切块，
        // 切块后由 AFTER_COMMIT 事件 + 定时补偿任务完成向量化。fire-and-forget 不阻塞 UI，
        // 避免"处理中"按钮长时间卡住（切块/向量化在后台进行，可在知识库管理页看进度）。
        void (async () => {
          try {
            const pending = await knowledgeBaseApi.getAllKnowledgeBases(undefined, 'CONVERTED');
            for (const kb of pending) {
              await knowledgeBaseApi.splitDocument(kb.id).catch(() => undefined);
            }
          } catch {
            // 触发失败不影响上传结果；用户可在知识库管理页手动"重新向量化"
          }
        })();
        setNotice(`批量上传完成：成功 ${result.success}，失败 ${result.failed}。文件正在后台处理，可在知识库列表查看进度。`);
        setTimeout(() => onUploadComplete({
          knowledgeBase: { id: 0, name: 'batch', category: '', fileSize: 0, contentLength: 0, docStatus: 'CONVERTED' },
          storage: { fileKey: '', fileUrl: '' },
          duplicate: false,
        }), 1200);
      } else {
        setNotice(`批量上传完成：成功 ${result.success}，失败 ${result.failed}`);
        setUploading(false);
      }
    } catch (err: unknown) {
      setError(getErrorMessage(err, '批量上传失败，请重试'));
      setUploading(false);
    }
  };

  return (
    <div className="max-w-6xl mx-auto">
      <PageHeader
        eyebrow="知识库"
        title="添加知识资料"
        description="上传技术文档或学习资料，处理完成后可直接提问，也可在模拟面试中使用。"
        onBack={onBack}
      />

      <div className="grid lg:grid-cols-[minmax(260px,300px)_1fr] gap-5 items-start">
        <aside className="space-y-4 lg:sticky lg:top-6">
          <FormSection title="上传方式">
            <SegmentedControl
              value={uploadMode}
              onChange={setUploadMode}
              options={[
                { value: 'single', label: '单文件' },
                { value: 'batch', label: '批量' },
              ]}
              className="w-full flex"
            />
          </FormSection>

          <FormSection title="可见范围">
            <div className="space-y-2">
              <OptionTile
                selected={accessibleBy === 'PRIVATE'}
                onClick={() => setAccessibleBy('PRIVATE')}
                title="仅自己可见"
                description="默认，仅你的账号可检索与管理"
                icon={<Lock className="w-4 h-4" />}
              />
              <OptionTile
                selected={accessibleBy === 'PUBLIC'}
                onClick={() => setAccessibleBy('PUBLIC')}
                title="所有用户可读"
                description="其他用户可以检索，但不能修改或删除"
                icon={<Globe className="w-4 h-4" />}
              />
            </div>
            {uploadMode === 'batch' && (
              <label className="block mt-4 text-sm text-stone-600 dark:text-stone-400">
                统一分类
                <input
                  type="text"
                  value={batchCategory}
                  onChange={(e) => setBatchCategory(e.target.value)}
                  placeholder="例如：Java 后端"
                  className="dark-input mt-1.5 w-full px-3 py-2 rounded-lg text-sm"
                />
              </label>
            )}
            {uploadMode === 'single' ? (
              <label className="block mt-4 text-sm text-stone-600 dark:text-stone-400">
                自动失效日期（可选）
                <input
                  type="date"
                  value={expireDate}
                  onChange={(e) => setExpireDate(e.target.value)}
                  className="dark-input mt-1.5 w-full px-3 py-2 rounded-lg text-sm"
                />
              </label>
            ) : (
              <p className="mt-4 text-xs leading-5 text-stone-400">
                批量上传暂不支持自动失效日期；需要设置时请使用单文件上传。
              </p>
            )}
          </FormSection>
        </aside>

        <div className="surface-card p-5 md:p-6">
          <FileUploadCard
            variant="embedded"
            title={uploadMode === 'batch' ? '选择文件' : '上传文件'}
            subtitle={
              uploadMode === 'batch'
                ? '支持一次选择多个文档，失败项不影响其余文件'
                : '上传后会自动处理，完成后即可使用'
            }
            accept=".pdf,.doc,.docx,.txt,.md,.csv,.xlsx,.xls"
            formatHint="PDF、DOCX、TXT、MD 等"
            maxSizeHint="单文件 ≤ 50MB"
            uploading={uploading}
            uploadButtonText={uploadMode === 'batch' ? '开始批量上传' : '开始上传'}
            showNameInput={uploadMode === 'single'}
            nameLabel="显示名称（可选）"
            namePlaceholder="留空则使用文件名"
            error={error}
            notice={notice}
            multiple={uploadMode === 'batch'}
            inputId={uploadMode === 'batch' ? 'kb-batch-upload-input' : 'kb-single-upload-input'}
            onUpload={handleUpload}
            onBatchUpload={handleBatchUpload}
          />

          {batchResults && (
            <div className="mt-6 pt-6 border-t border-stone-200 dark:border-stone-800">
              <h3 className="text-sm font-semibold text-stone-800 dark:text-stone-100 mb-3 flex items-center gap-2">
                <FileSpreadsheet className="w-4 h-4" />
                上传结果
              </h3>
              <div className="max-h-52 overflow-y-auto space-y-2 scrollbar-thin">
                {batchResults.map((item) => (
                  <div
                    key={item.filename}
                    className="flex items-start gap-2 text-xs rounded-lg bg-stone-50 dark:bg-stone-900/50 px-3 py-2"
                  >
                    <span className={item.status === 'success' ? 'text-emerald-600 font-medium' : 'text-red-500 font-medium'}>
                      {item.status === 'success' ? '成功' : '失败'}
                    </span>
                    <span className="text-stone-600 dark:text-stone-300 flex-1">{item.filename}</span>
                    {item.error && <span className="text-red-500">{item.error}</span>}
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
