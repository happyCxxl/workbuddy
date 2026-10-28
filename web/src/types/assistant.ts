// 灵犀助手接口类型：与 Python 服务契约对齐（ID 一律字符串）
export interface AssistantSession {
  id: string;
  work_dir: string;
  title: string;
  status: string;
  permission_preset: string;
  persona_version_id: string | null;
  updated_at: string;
}

// 会话事件：seq 单调，type 取值见后端事件登记表
export interface SessionEventItem {
  seq: number;
  type: string;
  payload: Record<string, unknown>;
}

// 权限档位：绑定会话
export type PermissionPreset = 'loose' | 'standard' | 'strict';

// 记忆条目
export interface AssistantMemory {
  id: string;
  kind: string;
  scope: string;
  work_dir: string | null;
  content: string;
  confidence: number;
  created_at: string;
}

// 人格八字段
export interface PersonaFields {
  name: string;
  opening: string;
  catchphrases: string[];
  tone: string;
  style: string;
  boundaries: string;
  user_name: string;
  user_profile: string;
}

// 人格版本
export interface PersonaVersionItem {
  version: number;
  active: boolean;
  created_at: string;
  fields: PersonaFields;
}

// 会话用量：调用次数与 token 汇总
export interface SessionUsage {
  calls: number;
  prompt_tokens: number;
  completion_tokens: number;
}
