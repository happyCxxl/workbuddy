package com.workbuddy.common.dto.response.stagecontent;

import lombok.Data;

import java.util.List;

/**
 * 产物内容响应：对比视图内容层数据源。
 * latest = 该次运行产物是否可用（按 task.productId 精确取产物；历史任务同样可展示）。
 *
 * <p>分页：total 是该次运行产物内容的**总条数**，items 只装当前页；不传分页参数时
 * 按默认上限返回首页，并由 truncated 标明是否被截断。
 *
 * <p>文档页过滤：传了 docPage 时 total 与 items 都只覆盖该页元素。
 *
 * @author cxxl
 */
@Data
public class StageContentVO {

    /** 文件结果 ID */
    private Long fileResultId;

    /** 环节（白名单 PARSE/STRUCTURE/PREPROCESS/CHUNK/EMBED） */
    private String stage;

    /** 任务 ID（无任务时为空） */
    private Long taskId;

    /** 该次运行产物是否可用（false = 无任务/无产物/无 artifactId，items 为空） */
    private Boolean latest;

    /** 本次生效的文档页过滤（未过滤时为空） */
    private Integer docPage;

    /** 内容项列表（当前页） */
    private List<StageContentItemVO> items;

    /** 该次运行产物内容总条数（不受分页影响；按 docPage 过滤后为该页条数） */
    private Integer total;

    /** 当前页码（从 1 起） */
    private Integer page;

    /** 当前页请求的条数上限 */
    private Integer limit;

    /** 本次响应是否被截断（还有内容未返回） */
    private Boolean truncated;
}
