import {useEffect, useState} from 'react';
import {Globe, Lock} from 'lucide-react';
import {
  knowledgeBaseApi,
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
import { enqueueKbBatchUpload } from '../stores/kbUploadQueue';

interface KnowledgeBaseUploadPageProps {
  onUploadComplete: (result: UploadKnowledgeBaseResponse) => void;
  onBack: () => void;
}

type UploadMode = 'single' | 'batch';

const NEW_CATEGORY = '__new__';

function CategoryField({
  value,
  categories,
  onChange,
}: {
  value: string;
  categories: string[];
  onChange: (next: string) => void;
}) {
  const [creating, setCreating] = useState(() => value !== '' && !categories.includes(value));
  const selectValue = creating ? NEW_CATEGORY : value;

  return (
    <div className="space-y-2">
      {categories.length > 0 && (
        <select
          id="kb-batch-category"
          value={selectValue}
          onChange={(event) => {
            const next = event.target.value;
            if (next === NEW_CATEGORY) {
              setCreating(true);
              if (categories.includes(value)) {
                onChange('');
              }
              return;
            }
            setCreating(false);
            onChange(next);
          }}
          className="dark-input w-full px-3 py-2 rounded-lg text-sm"
        >
          <option value="">不分类</option>
          {categories.map((category) => (
            <option key={category} value={category}>
              {category}
            </option>
          ))}
          <option value={NEW_CATEGORY}>新建分类…</option>
        </select>
      )}
      {(creating || categories.length === 0) && (
        <input
          id={categories.length === 0 ? 'kb-batch-category' : 'kb-batch-category-new'}
          type="text"
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder="例如：Java 后端"
          className="dark-input w-full px-3 py-2 rounded-lg text-sm"
        />
      )}
    </div>
  );
}

export default function KnowledgeBaseUploadPage({ onUploadComplete, onBack }: KnowledgeBaseUploadPageProps) {
  const [uploadMode, setUploadMode] = useState<UploadMode>('single');
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [batchCategory, setBatchCategory] = useState('');
  const [existingCategories, setExistingCategories] = useState<string[]>([]);
  const [accessibleBy, setAccessibleBy] = useState<DocumentAccessScope>('PRIVATE');
  const [expireDate, setExpireDate] = useState('');

  useEffect(() => {
    knowledgeBaseApi.getAllCategories()
      .then((list) => setExistingCategories(list.filter((item) => item.trim().length > 0)))
      .catch(() => setExistingCategories([]));
  }, []);

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
    enqueueKbBatchUpload(files, {
      category: batchCategory.trim() || undefined,
      accessibleBy,
    });
    setNotice(`已将 ${files.length} 个文件加入后台上传队列。请保持浏览器标签页打开，直到管理页提示全部接收完成。`);
    setTimeout(() => onUploadComplete({
      knowledgeBase: { id: 0, name: 'batch', category: '', fileSize: 0, contentLength: 0, docStatus: 'UPLOADED' },
      storage: { fileKey: '', fileUrl: '' },
      duplicate: false,
    }), 400);
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

          {uploadMode === 'batch' && (
            <FormSection title="统一分类" description="这批文件共用一个分类，可选择已有项或新建。">
              <CategoryField
                value={batchCategory}
                categories={existingCategories}
                onChange={setBatchCategory}
              />
            </FormSection>
          )}
        </aside>

        <div className="surface-card p-5 md:p-6">
          <FileUploadCard
            variant="embedded"
            title={uploadMode === 'batch' ? '选择文件' : '上传文件'}
            subtitle={
              uploadMode === 'batch'
                ? '可一次选择多个文档，也可拖入整个文件夹；失败项不影响其余文件'
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
        </div>
      </div>
    </div>
  );
}
