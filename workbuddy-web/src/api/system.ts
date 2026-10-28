import { http } from './http';
import type { StorageSourceSwitchVO, StorageSourceVO } from '@/types/system';

/** 系统设置接口（仅管理员） */

/** 查询存储源清单：含全部参数与运行时接入标记，不探活 */
export async function getStorageSourceList(): Promise<StorageSourceVO[]> {
  const response = await http.get<StorageSourceVO[]>('/system/storage-sources');
  return response.data;
}

/** 取启用影响面：只统计受影响的任务，不落库、不切换 */
export async function getStorageSourceCurrentPreview(id: string): Promise<StorageSourceSwitchVO> {
  const response = await http.post<StorageSourceSwitchVO>(
    `/system/storage-sources/${id}/current/preview`,
  );
  return response.data;
}

/** 启用存储源：成功后新上传与新任务写入该存储源 */
export async function updateStorageSourceCurrent(id: string): Promise<StorageSourceSwitchVO> {
  const response = await http.post<StorageSourceSwitchVO>(`/system/storage-sources/${id}/current`);
  return response.data;
}
