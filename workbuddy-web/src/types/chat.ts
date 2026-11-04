/** 工具调用行：名称、参数与结果 */
export interface ToolLine {
  name: string;
  args: string;
  result?: string;
  error?: string;
  /** 调用开始时间与耗时（毫秒） */
  startedAt?: number;
  duration?: string;
}

/** 待审批信息：request_id 用于恢复重放 */
export interface PermissionInfo {
  request_id: string;
  name: string;
  reason: string;
}

/** 消息：用户或可乐；可乐可带思考、工具与审批卡 */
export interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
  thinking: string;
  tools: ToolLine[];
  permission?: PermissionInfo;
  interrupted?: boolean;
  /** 该消息所属回合的 turn/start seq */
  turnSeq?: number;
}

/** 一轮 = 过程组（各步骤的思考/文本/工具）+ 最终回复；对齐 DSH 的轮次过程折叠 */
export interface TurnView {
  key: string;
  userMessages: ChatMessage[];
  steps: ChatMessage[];
  answer: ChatMessage | null;
  running: boolean;
  /** 该轮在事件日志里的 turn_seq（重试用） */
  turnSeq?: number;
}

/** 单次模型调用 */
export interface TurnCall {
  model: string;
  tokens: number;
  latency: string;
  time: string;
}

/** 单轮用量汇总 */
export interface TurnUsage {
  duration: string;
  tokens: number;
  prompt: number;
  completion: number;
  cacheHit: number;
  cacheMiss: number;
  models: Set<string>;
  calls: TurnCall[];
}
