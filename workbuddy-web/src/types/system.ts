// 系统设置契约：文件服务写入后端的数据源（接口仅管理员可访问）

/** 数据源状态码值：下拉只列已启用的数据源 */
export type StorageSourceStatus = 'ENABLED' | 'DISABLED';

/** 数据源：清单一行（后端 StorageSourceVO） */
export interface StorageSourceVO {
  id: string;
  name: string;
  /** 存储类型码值 */
  storageType: string;
  /** 存储类型显示名 */
  storageTypeName: string;
  status: StorageSourceStatus;
  /** 是否为当前启用的写入数据源 */
  current: boolean;
  /** 必填参数是否齐全 */
  configured: boolean;
  /** 是否已注册进运行时路由（未停用且参数齐全） */
  registered: boolean;
  /** 最近一次连接探测的结果；null 表示从未探测过 */
  probeOk: boolean | null;
  /** 最近一次连接探测的时间 */
  probeAt: string | null;
  /** 全部参数：键值对含密钥类参数 */
  params: Record<string, string>;
}

/** 启用影响面：取影响面与实际启用返回同一形状 */
export interface StorageSourceSwitchVO {
  /** 目标数据源 ID */
  targetSourceId: string;
  /** 目标数据源名称 */
  targetName: string;
  /** 目标数据源类型码值 */
  targetType: string | null;
  /** 取影响面返回当前启用的数据源 ID（未启用任何数据源时为空），实际启用返回该数据源 ID */
  currentSourceId: string | null;
  /** 当前启用的数据源名称 */
  currentName: string | null;
  /** 排队中且属于其他数据源的任务数 */
  queuedCount: number;
  /** 执行中且属于其他数据源的任务数 */
  runningCount: number;
  /** 影响面文案，确认弹窗正文首行 */
  message: string;
}
