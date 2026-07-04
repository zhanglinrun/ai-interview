import {useState} from 'react';
import {FileSpreadsheet, FileStack, Globe, Lock, Table2} from 'lucide-react';
import {
  knowledgeBaseApi,
  type BatchUploadItemResult,
  type DocumentAccessScope,
  type KnowledgeBaseType,
  type UploadKnowledgeBaseResponse,
} from '../api/knowledgebase';
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
  const [knowledgeBaseType, setKnowledgeBaseType] = useState<KnowledgeBaseType>('DOCUMENT_SEARCH');
  const [accessibleBy, setAccessibleBy] = useState<DocumentAccessScope>('PRIVATE');
  const [expireDate, setExpireDate] = useState('');

  const handleUpload = async (file: File, name?: string) => {
    setUploading(true);
    setError('');
    setNotice('');

    try {
      const data = await knowledgeBaseApi.uploadKnowledgeBase(
        file,
        name,
        undefined,
        knowledgeBaseType,
        { accessibleBy, expireDate: expireDate.trim() || undefined },
      );
      if (data.duplicate) {
        setNotice(`该文件已存在，对应知识库「${data.knowledgeBase.name}」，无需重复上传。`);
        setUploading(false);
        return;
      }
      if (knowledgeBaseType === 'DOCUMENT_SEARCH') {
        await knowledgeBaseApi.splitDocument(data.knowledgeBase.id);
      }
      onUploadComplete(data);
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
      setNotice(`批量上传完成：成功 ${result.success}，失败 ${result.failed}`);
      if (result.success > 0) {
        setTimeout(() => onUploadComplete({
          knowledgeBase: { id: 0, name: 'batch', category: '', fileSize: 0, contentLength: 0, docStatus: 'CONVERTED' },
          storage: { fileKey: '', fileUrl: '' },
          duplicate: false,
        }), 1200);
      } else {
        setUploading(false);
      }
    } catch (err: unknown) {
      setError(getErrorMessage(err, '批量上传失败，请重试'));
      setUploading(false);
    }
  };

  const isSpreadsheetOnly = knowledgeBaseType === 'DATA_QUERY';

  return (
    <div className="max-w-6xl mx-auto">
      <PageHeader
        eyebrow="知识库"
        title="上传文档"
        description="将面试资料、技术文档或表格导入知识库，供 RAG 检索与模拟面试使用。"
        onBack={onBack}
      />

      <div className="grid lg:grid-cols-[minmax(280px,340px)_1fr] gap-6 lg:gap-8 items-start">
        <aside className="space-y-4 lg:sticky lg:top-6">
          <FormSection title="上传方式">
            <SegmentedControl
              value={uploadMode}
              onChange={setUploadMode}
              options={[
                { value: 'single', label: '单文件' },
                { value: 'batch', label: '批量', disabled: isSpreadsheetOnly },
              ]}
              className="w-full flex"
            />
          </FormSection>

          <FormSection title="知识库类型" description="决定后续解析与检索方式">
            <div className="space-y-2">
              <OptionTile
                selected={knowledgeBaseType === 'DOCUMENT_SEARCH'}
                onClick={() => {
                  setKnowledgeBaseType('DOCUMENT_SEARCH');
                  setUploadMode('single');
                }}
                title="文档检索"
                description="PDF / Word / Markdown，切块后向量化"
                icon={<FileStack className="w-4 h-4" />}
              />
              <OptionTile
                selected={knowledgeBaseType === 'DATA_QUERY'}
                onClick={() => {
                  setKnowledgeBaseType('DATA_QUERY');
                  setUploadMode('single');
                }}
                title="数据查询"
                description="Excel / CSV，走 Text2SQL"
                icon={<Table2 className="w-4 h-4" />}
              />
            </div>
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
                title="公开可读"
                description="团队内其他用户可检索，不可改删"
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
            <label className="block mt-4 text-sm text-stone-600 dark:text-stone-400">
              到期日（可选）
              <input
                type="date"
                value={expireDate}
                onChange={(e) => setExpireDate(e.target.value)}
                className="dark-input mt-1.5 w-full px-3 py-2 rounded-lg text-sm"
              />
            </label>
          </FormSection>
        </aside>

        <div className="surface-card p-6 md:p-8">
          <FileUploadCard
            variant="embedded"
            title={uploadMode === 'batch' ? '选择文件' : '上传文件'}
            subtitle={
              uploadMode === 'batch'
                ? '支持一次选择多个文档，失败项不影响其余文件'
                : isSpreadsheetOnly
                  ? '上传表格文件用于结构化查询'
                  : '上传后将自动解析、切块并向量化'
            }
            accept={isSpreadsheetOnly ? '.csv,.xlsx,.xls,.tsv' : '.pdf,.doc,.docx,.txt,.md,.csv,.xlsx,.xls'}
            formatHint={isSpreadsheetOnly ? 'CSV、Excel' : 'PDF、DOCX、TXT、MD 等'}
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
