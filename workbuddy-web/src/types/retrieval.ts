/**
 * 检索与评测的类型（对齐后端 retrieval 包下的 DTO/VO）。
 *
 * @author cxxl
 */

/**
 * 检索入参（后端 SearchRequest）。
 *
 * <p>`versionId` + `ruleId` 是**测试台**的必填项（可检索候选冻结集）；
 * 生产检索不传它们，走回退链（在线版本行 → kb 默认 → 引擎基线）。
 */
export interface SearchRequest {
  query: string;
  /** 覆盖规则里的 topK；不传用规则值 */
  topK?: number;
  /** 按文件结果过滤 */
  documentId?: string;
  /** 按归属人过滤 */
  owner?: string;
  /** 按片类型过滤 */
  contentType?: string;
  /** 检索规则行 ID（wb_knowledge_pipeline_strategy_version，type=RETRIEVAL） */
  ruleId?: string;
  /** 索引版本行 ID */
  versionId?: string;
}

/** 检索命中（后端 SearchHitVO） */
export interface SearchHit {
  /** 片 ID（父片展开后为父片 ID） */
  chunkId: string;
  content: string;
  titlePath: string | null;
  /** 源元素 ID（JSON 串） */
  sourceElementIds: string | null;
  /** 文件结果 ID */
  documentId: string | null;
  contentType: string | null;
  parentChunkId: string | null;
  /** 分数：向量=相似度；HYBRID=RRF 融合分；**纯全文=null** */
  score: number | null;
  /** 是否父片展开（命中子片但返回父片全文） */
  isParent: boolean | null;
}

/** 检索响应（后端 SearchVO） */
export interface SearchResult {
  query: string;
  /** 规则 name-version（引擎基线为 baseline-v0） */
  ruleNameVersion: string;
  versionNo: string;
  elapsedMs: number | null;
  /**
   * 运行记录 ID（测试台检索必有值）。
   *
   * <p>后端已字符串化下发 —— 雪花 ID 19 位，作为数字读会丢精度，
   * 而勾选对比要把这个 ID 回传。
   */
  runId: string | null;
  hits: SearchHit[];
}

/** 检索运行记录（后端 RetrievalRunVO） */
export interface RetrievalRun {
  id: string;
  versionId: string;
  versionNo: string;
  ruleId: string | null;
  /** 规则 name-version */
  ruleNameVersion: string;
  query: string;
  elapsedMs: number;
  createTime: string | null;
}

/** 规则选优发布响应（后端 RetrievalRulePublishVO） */
export interface RetrievalRulePublishResult {
  versionId: string;
  versionNo: string;
  ruleId: string;
  ruleNameVersion: string;
}
