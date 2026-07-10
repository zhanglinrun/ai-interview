import type { ProviderTestResult } from './llmProvider';

/**
 * 用户「我的模型」（BYOK）回显。
 *
 * 明文 API Key 永不返回，仅 {@link maskedApiKey} 为脱敏提示（如 `****abcd`）。
 * 未配置时 {@link configured} 为 false，其余字段为 null。
 */
export interface MyProviderDTO {
  configured: boolean;
  baseUrl: string | null;
  chatModel: string | null;
  temperature: number | null;
  maskedApiKey: string | null;
}

/**
 * 保存用户「我的模型」请求体。baseUrl/chatModel 必填，temperature 可选。
 *
 * apiKey：首次配置必填；已配置后仅改 baseUrl/chatModel 时可省略，表示保持已存的 Key 不变。
 */
export interface SaveMyProviderRequest {
  baseUrl: string;
  apiKey?: string;
  chatModel: string;
  temperature?: number;
}

export type { ProviderTestResult };
