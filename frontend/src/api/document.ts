import { request } from './request';

export type DocumentAccessScope = 'PRIVATE' | 'PUBLIC';

export interface DocumentIngestionResult {
  documentId: number;
  versionId: number;
  status: string;
  segmentCount: number;
}

export interface DocumentVersionView {
  versionId: number;
  version: string;
  status: string | null;
  changelog: string | null;
  embeddingAttempt: number | null;
  embeddingTerminalFailure: boolean | null;
  createdAt: string;
  updatedAt: string;
}

export const documentApi = {
  upload(file: File, title?: string, category?: string, accessibleBy: DocumentAccessScope = 'PRIVATE', expireDate?: string) {
    const form = new FormData();
    form.append('file', file);
    if (title?.trim()) form.append('title', title.trim());
    if (category?.trim()) form.append('category', category.trim());
    form.append('accessibleBy', accessibleBy);
    if (expireDate?.trim()) form.append('expireDate', expireDate.trim());
    return request.upload<DocumentIngestionResult>('/api/v1/documents', form);
  },
  uploadVersion(documentId: number, file: File, changelog?: string) {
    const form = new FormData();
    form.append('file', file);
    if (changelog?.trim()) form.append('changelog', changelog.trim());
    return request.upload<DocumentIngestionResult>(`/api/v1/documents/${documentId}/versions`, form);
  },
  listVersions(documentId: number) {
    return request.get<DocumentVersionView[]>(`/api/v1/documents/${documentId}/versions`);
  },
  reindex(documentId: number) {
    return request.post<DocumentIngestionResult>(`/api/v1/documents/${documentId}/reindex`);
  },
};
