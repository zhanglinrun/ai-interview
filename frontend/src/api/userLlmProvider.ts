import request from './request';
import type {
  MyProviderDTO,
  SaveMyProviderRequest,
  ProviderTestResult,
} from '../types/userLlmProvider';

/**
 * 用户级 LLM Provider（BYOK）接口封装：读/存/删/测试当前登录用户的「我的模型」。
 * 全部走 JWT，后端按当前用户解析（`/api/llm-provider/mine`）。
 */
export const userLlmProviderApi = {
  getMine: () => request.get<MyProviderDTO>('/api/llm-provider/mine'),

  saveMine: (data: SaveMyProviderRequest) =>
    request.put<void>('/api/llm-provider/mine', data),

  deleteMine: () => request.delete<void>('/api/llm-provider/mine'),

  /** 用已保存的「我的模型」做一次最小 chat 连通性测试（后端读取已存配置，无需请求体）。 */
  testMine: () => request.post<ProviderTestResult>('/api/llm-provider/mine/test'),
};
