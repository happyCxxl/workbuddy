import { http } from './http';

/** 厂商：密钥只暴露环境变量名 */
export interface ModelVendor {
  id: string;
  code: string;
  display_name: string;
  base_url: string;
  api_key_masked: string | null;
  api_key_configured: boolean;
  timeout_ms: number | null;
  max_retries: number | null;
  enabled: boolean;
}

export type ModelCapability = 'CHAT' | 'EMBEDDING' | 'OCR' | 'RERANK';

/** 模型：厂商 + 模型名 + 能力 */
export interface GatewayModel {
  id: string;
  vendor_id: string;
  model_key: string;
  display_name: string;
  capability: ModelCapability;
  dimension: number | null;
  context_window: number | null;
  batch_limit: number | null;
  rpm_limit: number | null;
  tpm_limit: number | null;
  price_in: number | null;
  price_out: number | null;
  currency: string;
  features: Record<string, boolean>;
  enabled: boolean;
  status: string;
}

export interface UsageRow {
  key: string | null;
  calls: number;
  tokens: number;
  avg_latency_ms: number;
  failures: number;
}

export async function getVendors(): Promise<ModelVendor[]> {
  const response = await http.get<{ vendors: ModelVendor[] }>('/model-admin/vendors');
  return response.data.vendors;
}

export async function addVendor(body: Partial<ModelVendor>): Promise<ModelVendor> {
  const response = await http.post<ModelVendor>('/model-admin/vendors', body);
  return response.data;
}

export async function updateVendor(id: string, body: Partial<ModelVendor>): Promise<ModelVendor> {
  const response = await http.put<ModelVendor>(`/model-admin/vendors/${id}`, body);
  return response.data;
}

export async function deleteVendor(id: string): Promise<null> {
  const response = await http.delete<null>(`/model-admin/vendors/${id}`);
  return response.data;
}

export async function getModels(params?: Record<string, string>): Promise<GatewayModel[]> {
  const response = await http.get<{ models: GatewayModel[] }>('/model-admin/models', { params });
  return response.data.models;
}

export async function addModel(body: Record<string, unknown>): Promise<GatewayModel> {
  const response = await http.post<GatewayModel>('/model-admin/models', body);
  return response.data;
}

export async function updateModel(
  id: string,
  body: Record<string, unknown>,
): Promise<GatewayModel> {
  const response = await http.put<GatewayModel>(`/model-admin/models/${id}`, body);
  return response.data;
}

export async function updateModelStatus(id: string, enabled: boolean): Promise<GatewayModel> {
  const response = await http.patch<GatewayModel>(`/model-admin/models/${id}/status`, { enabled });
  return response.data;
}

export async function deleteModel(id: string): Promise<null> {
  const response = await http.delete<null>(`/model-admin/models/${id}`);
  return response.data;
}

export async function getModelTest(id: string): Promise<{ ok: boolean; message?: string }> {
  const response = await http.post<{ ok: boolean; message?: string }>(
    `/model-admin/models/${id}/test`,
  );
  return response.data;
}

export async function getUsage(groupBy: string): Promise<UsageRow[]> {
  const response = await http.get<{ rows: UsageRow[] }>('/model-admin/usage', {
    params: { group_by: groupBy },
  });
  return response.data.rows;
}

/** 当前可用的 Chat 模型：会话页选择用，任意登录用户可读 */
export interface AvailableModel {
  id: string;
  display_name: string;
  model_key: string;
  capability: ModelCapability;
  vendor_code: string;
  vendor_name: string;
}

export async function getAvailableModels(capability = 'CHAT'): Promise<AvailableModel[]> {
  const response = await http.get<{ models: AvailableModel[] }>('/model-admin/models/available', {
    params: { capability },
  });
  return response.data.models;
}
