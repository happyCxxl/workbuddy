import { http } from './http';
import type {
  AssistantMemory,
  AssistantSession,
  DirListing,
  ModelCallItem,
  PermissionPreset,
  PersonaFields,
  PersonaVersionItem,
  SessionEventItem,
  SessionUsage,
  SubSessionItem,
  Workspace,
} from '@/types/assistant';

/** 灵犀助手接口：会话、回合 SSE、记忆、人设与权限（HTTP 恒 200 + 信封，拦截器统一解包） */

/** 建会话：绑定当前启用人格版本与默认权限档；可按工作区归属 */
export async function addAssistantSession(
  workDir: string,
  title: string,
  workspaceId?: string,
): Promise<AssistantSession> {
  const response = await http.post<{ id: string }>('/sessions', {
    work_dir: workDir,
    title,
    workspace_id: workspaceId ?? null,
  });
  const session = await getAssistantSession(response.data.id);
  return session;
}

/** 工作区列表：新建在前 */
export async function getWorkspaces(): Promise<Workspace[]> {
  const response = await http.get<{ workspaces: Workspace[] }>('/workspaces');
  return response.data.workspaces;
}

/** 新增工作区：目录必须存在，名称缺省取目录名 */
export async function addWorkspace(path: string, name = ''): Promise<Workspace> {
  const response = await http.post<Workspace>('/workspaces', { path, name });
  return response.data;
}

/** 重命名工作区：只改显示名，不动目录与会话归属 */
export async function updateWorkspace(workspaceId: string, name: string): Promise<Workspace> {
  const response = await http.patch<Workspace>(`/workspaces/${workspaceId}`, { name });
  return response.data;
}

/** 最近会话列表：对话页左栏 */
export async function getAssistantSessions(): Promise<AssistantSession[]> {
  const response = await http.get<{ sessions: AssistantSession[] }>('/sessions');
  return response.data.sessions;
}

/** 重命名会话 */
export async function updateAssistantSession(sessionId: string, title: string): Promise<void> {
  await http.patch(`/sessions/${sessionId}`, { title });
}

/** 逻辑删除单条会话：只从列表消失，数据库行与事件保留 */
export async function deleteAssistantSession(sessionId: string): Promise<void> {
  await http.delete(`/sessions/${sessionId}`);
}

/** 删除工作区：其下会话记录一并删除（只删记录，磁盘文件不动） */
export async function deleteWorkspace(workspaceId: string): Promise<number> {
  const response = await http.delete<{ deleted: number }>(`/workspaces/${workspaceId}`);
  return response.data.deleted;
}

/** 目录浏览：不传 path 时为盘符列表，传 path 时列子目录 */
export async function getWorkspaceDirs(path?: string): Promise<DirListing> {
  const response = await http.get<DirListing>('/fs/dirs', {
    params: path ? { path } : {},
  });
  return response.data;
}

/** 读会话：工作目录、权限档与绑定的人格版本 */
export async function getAssistantSession(sessionId: string): Promise<AssistantSession> {
  const response = await http.get<AssistantSession>(`/sessions/${sessionId}`);
  return response.data;
}

/** 切换本会话权限档：只影响该会话 */
export async function updateSessionPermission(
  sessionId: string,
  preset: PermissionPreset,
): Promise<void> {
  await http.put(`/sessions/${sessionId}/permission`, { preset });
}

/** 事件重放：界面重建 = afterSeq 0；断线补齐 = 客户端游标 */
export async function getSessionEvents(
  sessionId: string,
  afterSeq = 0,
): Promise<SessionEventItem[]> {
  const events: SessionEventItem[] = [];
  let cursor = afterSeq;
  for (;;) {
    const response = await http.get<{ events: SessionEventItem[] }>(
      `/sessions/${sessionId}/events`,
      {
        params: { after_seq: cursor, limit: 500 },
      },
    );
    const batch = response.data.events;
    const next = batch.at(-1)?.seq;
    events.push(...batch);
    if (batch.length < 500 || next === undefined || next <= cursor) {
      return events;
    }
    cursor = next;
  }
}

/** 模型调用明细：轨迹时间线里的模型节点 */
export async function getSessionCalls(sessionId: string): Promise<ModelCallItem[]> {
  const response = await http.get<{ calls: ModelCallItem[] }>(`/sessions/${sessionId}/calls`);
  return response.data.calls;
}

/** 申请停止：当前回合在下一步或流式块边界停止 */
export async function updateTurnStop(sessionId: string): Promise<void> {
  await http.post(`/sessions/${sessionId}/turns/stop`);
}

/** 会话用量：调用次数与 token 汇总 */
export async function getSessionUsage(sessionId: string): Promise<SessionUsage> {
  const response = await http.get<SessionUsage>(`/sessions/${sessionId}/usage`);
  return response.data;
}

/** 提交后台任务：立即返回，结果在后台会话里跑 */
export async function addSubSession(sessionId: string, task: string, title = ''): Promise<string> {
  const response = await http.post<{ id: string }>(`/sessions/${sessionId}/subsessions`, {
    task,
    title,
  });
  return response.data.id;
}

/** 父会话的后台任务列表（新建在前） */
export async function getSubSessions(sessionId: string): Promise<SubSessionItem[]> {
  const response = await http.get<{ subsessions: SubSessionItem[] }>(
    `/sessions/${sessionId}/subsessions`,
  );
  return response.data.subsessions;
}

/** 单个后台任务详情 */
export async function getSubSession(subSessionId: string): Promise<SubSessionItem> {
  const response = await http.get<SubSessionItem>(`/subsessions/${subSessionId}`);
  return response.data;
}

/** 记忆列表：传 workDir 查可见范围（全局 + 该目录） */
export async function getMemories(workDir?: string): Promise<AssistantMemory[]> {
  const response = await http.get<{ memories: AssistantMemory[] }>('/memories', {
    params: workDir ? { work_dir: workDir } : {},
  });
  return response.data.memories;
}

/** 删除记忆：物理移除 */
export async function deleteMemory(memoryId: string): Promise<void> {
  await http.delete(`/memories/${memoryId}`);
}

/** 启用中的人格版本 */
export async function getPersona(): Promise<{ version: number; fields: PersonaFields }> {
  const response = await http.get<{ version: number; fields: PersonaFields }>('/persona');
  return response.data;
}

/** 保存人格为新版本（自动启用） */
export async function addPersonaVersion(fields: PersonaFields): Promise<number> {
  const response = await http.put<{ version: number }>('/persona', { fields });
  return response.data.version;
}

/** 全部人格版本（倒序） */
export async function getPersonaVersions(): Promise<PersonaVersionItem[]> {
  const response = await http.get<{ versions: PersonaVersionItem[] }>('/persona/versions');
  return response.data.versions;
}

/** 单版本快照 */
export async function getPersonaVersion(
  version: number,
): Promise<{ version: number; fields: PersonaFields }> {
  const response = await http.get<{ version: number; fields: PersonaFields }>(
    `/persona/versions/${version}`,
  );
  return response.data;
}

/** 启用指定版本：只影响新会话 */
export async function updatePersonaActive(version: number): Promise<void> {
  await http.post(`/persona/versions/${version}/activate`);
}

/** 回合流回调：事件到达、流结束与失败 */
export interface TurnStreamHandlers {
  onEvent: (type: string, payload: Record<string, unknown>) => void;
  onDone: () => void;
  onError: (message: string) => void;
}

/** 令牌与后端地址：SSE 走原生 fetch（axios 不暴露流式 body） */
const TOKEN_KEY = 'knowledge-token';

/** SSE 解析状态：当前事件类型与 DONE 标记 */
interface SseParser {
  type: string;
  done: boolean;
}

/** 解析一帧：event 行更新类型，data 行派发事件或识别 DONE；返回是否已结束 */
function applyFrame(frame: string, parser: SseParser, handlers: TurnStreamHandlers): boolean {
  for (const line of frame.split('\n')) {
    if (line.startsWith('event: ')) {
      parser.type = line.slice('event: '.length);
      continue;
    }
    if (!line.startsWith('data: ')) {
      continue;
    }
    const data = line.slice('data: '.length);
    if (data === '[DONE]') {
      parser.done = true;
      return true;
    }
    try {
      handlers.onEvent(parser.type, JSON.parse(data) as Record<string, unknown>);
    } catch {
      handlers.onError('事件帧解析失败');
      return true;
    }
    parser.type = '';
  }
  return false;
}

/** 解析 SSE 帧：event 行 + data 行；data 为 [DONE] 时收尾 */
async function readSseFrames(response: Response, handlers: TurnStreamHandlers): Promise<void> {
  if (!response.ok) {
    handlers.onError(`流式接口失败（HTTP ${String(response.status)}）`);
    return;
  }
  if (!response.body) {
    handlers.onError('流式接口没有响应体');
    return;
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder('utf-8');
  const parser: SseParser = { type: '', done: false };
  let buffer = '';
  while (!parser.done) {
    const chunk = await reader.read();
    if (chunk.done) {
      break;
    }
    buffer += decoder.decode(chunk.value, { stream: true });
    let boundary = buffer.indexOf('\n\n');
    while (boundary >= 0) {
      const frame = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      if (applyFrame(frame, parser, handlers)) {
        break;
      }
      boundary = buffer.indexOf('\n\n');
    }
  }
  handlers.onDone();
}

/** 发送一条消息并消费回合 SSE 流；返回可取消的 AbortController */
export function addTurnStream(
  sessionId: string,
  content: string,
  handlers: TurnStreamHandlers,
): AbortController {
  const controller = new AbortController();
  const token = localStorage.getItem(TOKEN_KEY) ?? sessionStorage.getItem(TOKEN_KEY) ?? '';
  void (async () => {
    try {
      const response = await fetch(`/sessions/${sessionId}/turns`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ content }),
        signal: controller.signal,
      });
      await readSseFrames(response, handlers);
    } catch (error) {
      if (!controller.signal.aborted) {
        handlers.onError(error instanceof Error ? error.message : '流式请求失败');
      }
    }
  })();
  return controller;
}

/** 审批裁决后恢复重放（SSE 续流） */
export function addTurnResume(
  sessionId: string,
  requestId: string,
  outcome: 'allowed-once' | 'allowed-always' | 'rejected',
  handlers: TurnStreamHandlers,
): AbortController {
  const controller = new AbortController();
  const token = localStorage.getItem(TOKEN_KEY) ?? sessionStorage.getItem(TOKEN_KEY) ?? '';
  void (async () => {
    try {
      const response = await fetch(`/sessions/${sessionId}/turns/resume`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
        body: JSON.stringify({ request_id: requestId, outcome }),
        signal: controller.signal,
      });
      await readSseFrames(response, handlers);
    } catch (error) {
      if (!controller.signal.aborted) {
        handlers.onError(error instanceof Error ? error.message : '恢复请求失败');
      }
    }
  })();
  return controller;
}
