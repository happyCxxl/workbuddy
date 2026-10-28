// 统一响应体（与后端 R<T> 对齐：code=0 成功，错误语义由 code 表达）
export interface R<T> {
  code: number;
  msg: string;
  data: T;
  timestamp: number | null;
}

// 分页结果（与后端 MyBatis-Plus IPage<T> 对齐）
export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}
