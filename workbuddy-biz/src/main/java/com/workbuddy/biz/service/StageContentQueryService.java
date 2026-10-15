package com.workbuddy.biz.service;

import com.workbuddy.common.dto.request.stage.ChunkContentFilter;
import com.workbuddy.common.dto.response.stagecontent.StageContentVO;

/**
 * 产物内容查询：对比视图内容层数据源，纯读。
 *
 * @author cxxl
 */
public interface StageContentQueryService {

    /** 分页默认每页条数（不传 limit 时） */
    int DEFAULT_LIMIT = 200;

    /** 分页每页条数上限（传得再大也按此截断，避免一次取回超大文档的全部内容） */
    int MAX_LIMIT = 1000;

    /**
     * 环节产物内容：白名单 PARSE/STRUCTURE/PREPROCESS/CHUNK/EMBED（非法 40001）；
     * 按 task.productId 精确取该次运行产物，历史任务同样可展示（latest=该次运行产物是否可用）。
     *
     * <p>分页：page 从 1 起、limit 为每页条数（缺省用默认上限，超过上限按上限截断）；
     * 响应里的 total 始终是该次运行产物内容的总条数，truncated 标明是否还有内容未返回。
     *
     * <p>文档页过滤：docPage 指定后只回该页元素，total 与翻页随之按该页口径给
     * （原文预览与解析结果按页联动用）。
     *
     * <p>状态过滤：status 以逗号分隔指定处置状态（如 `EXCLUDED_TOC,REPEATED`），只回这些状态的元素；
     * 与文档页过滤同一原则 —— 先过滤再分页，total 与 truncated 都按过滤后的口径给。
     *
     * <p>切片过滤：chunkFilter 指定内容类型 / 是否兜底 / 有无父片，**过滤与分页都下推到 kb_chunk**
     * （切片集合片数可能很大，不再整集合读进内存再分页），total 与 truncated 同样按过滤后的口径给。
     *
     * @param fileResultId 文件结果 ID（不存在 40432）
     * @param stage        环节（必填，白名单校验）
     * @param taskId       可选：缺省取最新任务；不属于该文件该环节 40001
     * @param docPage      可选：只取该文档页的元素（可空，按全部）
     * @param page         页码（可空，按 1）
     * @param limit        每页条数（可空，按默认上限）
     * @param status       可选：只取这些处置状态的元素（逗号分隔；空按全部）
     * @param chunkFilter  可选：切片内容过滤（仅 CHUNK 生效；空按不过滤）
     * @return 产物内容（当前页）
     */
    StageContentVO stageContent(Long fileResultId, String stage, Long taskId, Long docPage, Integer page,
                                Integer limit, String status, ChunkContentFilter chunkFilter);
}
