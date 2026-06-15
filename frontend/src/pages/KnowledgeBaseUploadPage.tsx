import { useState } from 'react';
import { knowledgeBaseApi } from '../api/knowledgebase';
import type { UploadKnowledgeBaseResponse } from '../api/knowledgebase';
import FileUploadCard from '../components/FileUploadCard';

interface KnowledgeBaseUploadPageProps {
  onUploadComplete: (result: UploadKnowledgeBaseResponse) => void;
  onBack: () => void;
}

export default function KnowledgeBaseUploadPage({ onUploadComplete, onBack }: KnowledgeBaseUploadPageProps) {
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const handleUpload = async (file: File, name?: string) => {
    setUploading(true);
    setError('');
    setNotice('');

    try {
      const data = await knowledgeBaseApi.uploadKnowledgeBase(file, name);
      if (data.duplicate) {
        setNotice(`该文件已存在，对应知识库「${data.knowledgeBase.name}」，无需重复上传。`);
        setUploading(false);
        return;
      }
      onUploadComplete(data);
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : '上传失败，请重试';
      setError(errorMessage);
      setUploading(false);
    }
  };

  return (
    <FileUploadCard
      title="上传知识库"
      subtitle="上传文档，AI 将基于知识库内容回答您的问题"
      accept=".pdf,.doc,.docx,.txt,.md"
      formatHint="支持 PDF、DOCX、DOC、TXT、MD"
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
  );
}
