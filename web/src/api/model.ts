import { http } from './http';
import type { ModelItem, ModelQuery, ModelSavePayload } from '@/types/model';

/** 模型管理接口：模型目录的清单与写入、启停 */

/** 查询模型清单：支持按能力 / 状态 / 关键字过滤，返回目录视图字段 */
export async function getModelList(query: ModelQuery = {}): Promise<ModelItem[]> {
  const response = await http.get<ModelItem[]>('/model/models', { params: query });
  return response.data;
}

/** 新增模型：厂商码必须在配置里，唯一键不冲突，长度、价格与限额非负，能力取值在字典内 */
export async function addModel(payload: ModelSavePayload): Promise<ModelItem> {
  const response = await http.post<ModelItem>('/model/models', payload);
  return response.data;
}

/** 编辑模型：覆盖写业务列，长度、价格与限额清空即写空 */
export async function updateModel(id: string, payload: ModelSavePayload): Promise<ModelItem> {
  const response = await http.put<ModelItem>(`/model/models/${id}`, payload);
  return response.data;
}

/** 启停模型：只改状态列（取值 ENABLED / DISABLED） */
export async function updateModelStatus(id: string, status: string): Promise<ModelItem> {
  const response = await http.patch<ModelItem>(`/model/models/${id}/status`, { status });
  return response.data;
}
