import { useState } from 'react';
import { knowledgeBaseApi, type DocumentAccessScope, type KnowledgeBaseType } from '../api/knowledgebase';
import type { UploadKnowledgeBaseResponse } from '../api/knowledgebase';
import { getErrorMessage } from '../api/request';
import FileUploadCard from '../components/FileUploadCard';

interface KnowledgeBaseUploadPageProps {
  onUploadComplete: (result: UploadKnowledgeBaseResponse) => void;
  onBack: () => void;
}

export default function KnowledgeBaseUploadPage({ onUploadComplete, onBack }: KnowledgeBaseUploadPageProps) {
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
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
        {
          accessibleBy,
          expireDate: expireDate.trim() || undefined,
        },
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

  const isSpreadsheetOnly = knowledgeBaseType === 'DATA_QUERY';

  return (
    <div className="space-y-4">
      <div className="rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 p-4">
        <p className="text-sm font-medium text-slate-700 dark:text-slate-200 mb-2">知识库类型</p>
        <div className="flex flex-wrap gap-3 text-sm">
          <label className="inline-flex items-center gap-2 cursor-pointer">
            <input
              type="radio"
              name="kbType"
              checked={knowledgeBaseType === 'DOCUMENT_SEARCH'}
              onChange={() => setKnowledgeBaseType('DOCUMENT_SEARCH')}
            />
            文档检索（切块 + 向量化）
          </label>
          <label className="inline-flex items-center gap-2 cursor-pointer">
            <input
              type="radio"
              name="kbType"
              checked={knowledgeBaseType === 'DATA_QUERY'}
              onChange={() => setKnowledgeBaseType('DATA_QUERY')}
            />
            数据查询（Excel/CSV → Text2SQL，不向量化）
          </label>
        </div>
      </div>

      <div className="rounded-xl border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 p-4 space-y-3">
        <p className="text-sm font-medium text-slate-700 dark:text-slate-200">访问与有效期</p>
        <div className="flex flex-wrap gap-4 text-sm">
          <label className="inline-flex items-center gap-2 cursor-pointer">
            <input
              type="radio"
              name="kbAccess"
              checked={accessibleBy === 'PRIVATE'}
              onChange={() => setAccessibleBy('PRIVATE')}
            />
            仅自己可见
          </label>
          <label className="inline-flex items-center gap-2 cursor-pointer">
            <input
              type="radio"
              name="kbAccess"
              checked={accessibleBy === 'PUBLIC'}
              onChange={() => setAccessibleBy('PUBLIC')}
            />
            公开可读
          </label>
        </div>
        <label className="block text-sm text-slate-600 dark:text-slate-300">
          到期日（可选）
          <input
            type="date"
            value={expireDate}
            onChange={(e) => setExpireDate(e.target.value)}
            className="mt-1 block w-full max-w-xs rounded-lg border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 px-3 py-2 text-sm"
          />
        </label>
      </div>

      <FileUploadCard
        title="上传知识库"
        subtitle={isSpreadsheetOnly
          ? '上传 Excel/CSV，用于结构化数据查询（不向量化）'
          : '上传文档，AI 将基于知识库内容回答您的问题'}
        accept={isSpreadsheetOnly ? '.csv,.xlsx,.xls,.tsv' : '.pdf,.doc,.docx,.txt,.md,.csv,.xlsx,.xls'}
        formatHint={isSpreadsheetOnly ? '支持 CSV、Excel' : '支持 PDF、DOCX、DOC、TXT、MD、CSV、Excel'}
        maxSizeHint="最大 50MB"
        uploading={uploading}
        uploadButtonText="开始上传"
        selectButtonText="选择文件"
        showNameInput={true}
        nameLabel="知识库名称（可选）"
        namePlaceholder="留空则使用文件名"
        error={error}
        notice={notice}
        onUpload={handleUpload}
        onBack={onBack}
      />
    </div>
  );
}
