package com.workbuddy.biz.controller.knowledge;

import com.workbuddy.biz.service.StageContentQueryService;
import com.workbuddy.common.dto.request.stage.ChunkContentFilter;
import com.workbuddy.common.dto.request.stage.StageDetailQueryDto;
import com.workbuddy.common.dto.response.R;
import com.workbuddy.common.dto.response.stagecontent.StageContentVO;
import com.workbuddy.common.utils.NullUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 环节产物内容 Controller：各环节共用的产物内容（白名单五环节）。
 *
 * <p>解析环节自己的触发与详情在 {@link KnowledgeFileParseController}；本类只承载五环节共用的内容读取。
 *
 * @author cxxl
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/file-results")
public class KnowledgeFileStageController {

    private final StageContentQueryService stageContentQueryService;

    /**
     * 产物内容：某一环节某次运行产物里的内容项，按页取。
     *
     * <p>分页是必需的：一次取回整份内容会把大文档的元素全部塞进浏览器。
     * 不传分页参数时按第 1 页 + 默认上限返回，响应里的 total 与 truncated 说明总量与是否还有。
     * docPage 按文档页收窄（原文预览与解析结果按页联动用），收窄后 total 即该页条数。
     *
     * @param fileResultId 文件结果 ID（路径参数）
     * @param stage        环节（白名单 PARSE/STRUCTURE/PREPROCESS/CHUNK/EMBED）
     * @param query        查询条件：taskId 可选（缺省最新任务）；docPage 可选（只取该文档页）；
     *                     status 可选（逗号分隔，只取这些处置状态）；page 从 1 起；limit 为每页条数；
     *                     contentType / fallback / hasParent 可选，只对 CHUNK 生效（按类型 / 兜底 / 父子收窄）
     * @return 产物内容（当前页）
     * @apiNote 文件结果不存在 40432；环节非法或任务不属于该文件该环节 40001
     */
    @GetMapping("/{fileResultId}/stage-content")
    public R<StageContentVO> stageContent(
            @PathVariable("fileResultId") Long fileResultId,
            @RequestParam("stage") String stage,
            StageDetailQueryDto query) {
        return R.ok(stageContentQueryService.stageContent(fileResultId, stage, query.getTaskId(),
                docPageOf(query), query.getPage(), query.getLimit(), query.getStatus(), chunkFilterOf(query)));
    }

    /** 切片内容过滤条件（三项都空时按不过滤） */
    private ChunkContentFilter chunkFilterOf(StageDetailQueryDto query) {
        return new ChunkContentFilter(query.getContentType(), query.getFallback(), query.getHasParent());
    }

    /**
     * 取本次请求要过滤的文档页。
     *
     * <p>页码比较在服务层按 Long 做：HTTP 来的数是大整数，元素页码在产物里是 Integer，
     * 类型不一致会让"第 N 页"永远匹配不上。
     */
    private Long docPageOf(StageDetailQueryDto query) {
        Integer docPage = query.getDocPage();
        return NullUtil.isNull(docPage) || docPage < 1 ? null : docPage.longValue();
    }
}
