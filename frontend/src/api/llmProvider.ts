import request from './request';
import type {
  ProviderItem,
  CreateProviderRequest,
  UpdateProviderRequest,
  ProviderTestResult,
  DefaultProvider,
} from '../types/llmProvider';

export const llmProviderApi = {
  list: () => request.get<ProviderItem[]>('/api/llm-provider/list'),

  get: (id: string) => request.get<ProviderItem>(`/api/llm-provider/${id}`),

  create: (data: CreateProviderRequest) =>
    request.post<void>('/api/llm-provider', data),

  update: (id: string, data: UpdateProviderRequest) =>
    request.put<void>(`/api/llm-provider/${id}`, data),

  delete: (id: string) =>
    request.delete<void>(`/api/llm-provider/${id}`),

  test: (id: string) =>
    request.post<ProviderTestResult>(`/api/llm-provider/${id}/test`),

  reload: () =>
    request.post<void>('/api/llm-provider/reload'),

  getDefaultProvider: () =>
    request.get<DefaultProvider>('/api/llm-provider/default-provider'),

  updateDefaultProvider: (data: DefaultProvider) =>
    request.put<void>('/api/llm-provider/default-provider', data),

  updateDefaultEmbeddingProvider: (data: DefaultProvider) =>
    request.put<void>('/api/llm-provider/default-embedding-provider', data),
};
