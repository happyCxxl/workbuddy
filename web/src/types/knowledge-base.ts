// 知识库状态码值（与后端 KnowledgeBaseStatus 对齐）
export const KB_STATUS_ACTIVE = 1;
export const KB_STATUS_DISABLED = 0;

/**
 * 知识库名称长度上限（与后端 `KnowledgeBaseCreateDto` / `KnowledgeBaseUpdateDto` 的 `@Size` 对齐）。
 *
 * <p>**14 是按卡片标题行的可用宽度倒推出来的**：列宽下限 380px 的卡片内宽 344px，
 * 扣掉立方体图标（38px）、两个 11px 间距与状态标签「已启用」（约 68px），留给库名约 **216px**；
 * 库名 15px 字，汉字全宽即 15px/字 → **216 ÷ 15 = 14 字**。数字/字母比汉字窄，任何
 * 14 字以内的名字都不会超宽 —— 即"合法名称必然整行显示完整"。
 *
 * <p>**不做省略号截断**：靠"长度上限保证显示得下"。
 * 名称是标签，就该短；说明（{@link KB_DESCRIPTION_MAX}，64）才是用来写细节的。
 */
export const KB_NAME_MAX = 14;

/**
 * 业务场景说明长度上限（与后端 DTO 的 `@Size` 对齐）。
 *
 * <p>**比名称更短**：这段文字要显示在列宽下限 380px 的卡片上（12px 字约 28 字/行），
 * 64 字约 2 行 —— 输入时就拦住上限，不需要省略号。
 */
export const KB_DESCRIPTION_MAX = 64;

/** 知识库卡片展示的业务字段（对齐后端 KnowledgeBaseVO） */
export interface KnowledgeBase {
  /** 知识库主键（后端雪花 Long 以字符串下发） */
  id: string;
  /** 知识库名称 */
  name: string;
  /** 业务场景说明 */
  description: string | null;
  /** 状态码值：1 启用 / 0 停用 */
  status: number;
  /**
   * 文档总数：wb_file_result 记录数。
   * 一次提交 = 一个任务 = 一行，同一文件重复提交会各占一行，故为提交次数而非去重文件数。
   */
  documentCount: number | null;
  /**
   * 三件套绑定的策略版本摘要（`name-version` 合成串，如 embed-nocache-v1；无绑定为 null）。
   *
   * <p>这三条后端列表接口**下发**（见 KnowledgeBaseVO），取值形态一致
   * （都是「有绑定给串、无绑定给 null」），可选性也保持一致。
   */
  preprocessStrategyVersion: string | null;
  chunkStrategyVersion: string | null;
  embedStrategyVersion: string | null;
  /**
   * 三件套绑定的版本 ID（无绑定为 null）。
   *
   * <p>详情接口（GET /knowledge-base/{id}）才带这三个，列表接口只有上面的摘要字段。
   * 编辑对话框要用 ID 回填选择器 —— 摘要带不出 ID。
   */
  preprocessStrategyVersionId?: string | null;
  chunkStrategyVersionId?: string | null;
  embedStrategyVersionId?: string | null;
  /** 策略绑定开关：1 开启（触发走本库绑定策略）/ 0 关闭（测评模式，触发须显式选策略） */
  strategyBindingEnabled: number | null;
  /** 当前已发布索引版本号（如 v3；未发布为 null） */
  publishedIndexVersion: string | null;
  /** 最近更新时间（ISO 字符串） */
  updateTime: string | null;
}

/** 知识库统计概览（仅统计未删除数据） */
export interface KnowledgeBaseStats {
  /** 知识库总数 */
  knowledgeBaseCount: number;
  /** 启用中的知识库数 */
  enabledCount: number;
  /** 文档总数（提交任务数） */
  documentCount: number;
}

/** 列表排序口径（与后端 KnowledgeBaseSort 对齐） */
export type KnowledgeBaseSort = 'DEFAULT' | 'UPDATED' | 'NAME';

/** 知识库分页入参 */
export interface KnowledgeBasePageQuery {
  current: number;
  size: number;
  /** 名称模糊关键字 */
  name?: string;
  /** 状态过滤：1 启用 / 0 停用；不传不过滤 */
  status?: number;
  /** 排序口径；不传按后端默认（新建的在前） */
  sort?: KnowledgeBaseSort;
}

/** 创建知识库入参（后端一律落普通库并默认启用，状态与策略绑定不在此处） */
export interface KnowledgeBaseCreateRequest {
  /** 名称，必填，≤128 */
  name: string;
  /** 业务场景说明，≤512 */
  description?: string;
  /** 策略绑定开关：1 开启（默认）/ 0 关闭 */
  strategyBindingEnabled?: number;
}

/** 更新知识库入参（仅名称/说明/策略绑定开关；停用启用有独立接口） */
export interface KnowledgeBaseUpdateRequest {
  /** 知识库 ID（后端 DTO 要求，与路径参数一致） */
  id: string;
  /** 名称，必填，≤128 */
  name: string;
  /** 业务场景说明，≤512 */
  description?: string;
  /** 策略绑定开关：1 开启 / 0 关闭 */
  strategyBindingEnabled?: number;
}
