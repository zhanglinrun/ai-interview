import { useState } from 'react';
import { resumeApi } from '../api/resume';
import { getErrorMessage } from '../api/request';
import FileUploadCard from '../components/FileUploadCard';

interface UploadPageProps {
  onUploadComplete: (resumeId: number) => void;
}

export default function UploadPage({ onUploadComplete }: UploadPageProps) {
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');

  const handleUpload = async (file: File) => {
    setUploading(true);
    setError('');

    try {
      const data = await resumeApi.uploadAndAnalyze(file);

      // 异步模式：只检查上传是否成功（storage 信息）
      if (!data.storage || !data.storage.resumeId) {
        throw new Error('上传失败，请重试');
      }

      // 上传成功，跳转到简历库（分析在后台进行）
      onUploadComplete(data.storage.resumeId);
    } catch (err) {
      setError(getErrorMessage(err));
      setUploading(false);
    }
  };

  return (
    <FileUploadCard
      title="上传简历"
      subtitle="上传后可查看简历分析，并用于后续模拟面试。"
      accept=".pdf,.doc,.docx,.txt"
      formatHint="支持 PDF、DOCX、TXT"
      maxSizeHint="不超过 10 MB"
      uploading={uploading}
      uploadButtonText="上传并分析"
      selectButtonText="选择简历文件"
      error={error}
      onUpload={handleUpload}
    />
  );
}
