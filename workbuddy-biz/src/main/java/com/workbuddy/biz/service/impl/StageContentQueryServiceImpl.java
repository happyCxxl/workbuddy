package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.StageContentQueryService;
import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingRecordDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.support.stagecontent.ChunkStageContentSupport;
import com.workbuddy.biz.service.support.stagecontent.EmbedStageContentSupport;
import com.workbuddy.biz.service.support.access.FileResultAccessGuard;
import com.workbuddy.biz.service.support.stagecontent.ParseStageContentSupport;
import com.workbuddy.biz.service.support.stagecontent.PreprocessStageContentSupport;
import com.workbuddy.biz.service.support.artifact.StageArtifactReader;
import com.workbuddy.biz.service.support.stagecontent.StageContentPaginationSupport;
import com.workbuddy.biz.service.support.stagecontent.StructureStageContentSupport;
import com.workbuddy.biz.service.support.common.TaskDetailSupport;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.request.stage.ChunkContentFilterDTO;
import com.workbuddy.common.dto.response.stagecontent.StageContentItemVO;
import com.workbuddy.common.dto.response.stagecontent.StageContentVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 产物内容查询实现：
 * 白名单五环节；按 task.productId 精确取该次运行的产物内容（与详情接口同口径，历史任务同样可展示）；
 * 对齐键：PARSE/STRUCTURE/PREPROCESS=elementId、CHUNK/EMBED=顺序号。
 *
 * <p>本类只做环节/任务/产物解析、空骨架、错误降级与分页编排；元素映射在各环节的 Support，
 * 分页与过滤口径在 {@link StageContentPaginationSupport}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StageContentQueryServiceImpl implements StageContentQueryService {

    private final WbPipelineProductDbService pipelineProductDbService;
    private final WbChunkSetDbService chunkSetDbService;
    private final WbChunkDbService chunkDbService;
    private final WbEmbeddingSetDbService embeddingSetDbService;
    private final WbEmbeddingRecordDbService embeddingRecordDbService;
    private final FileStorage fileStorage;
    private final TaskDetailSupport detailSupport;
    private final FileResultAccessGuard accessGuard;

    /**
     * 环节内容查询（不带状态与切片过滤的重载）。
     *
     * @param fileResultId 文件结果 ID
     * @param stage        环节名
     * @param taskId       任务 ID（可空）
     * @param docPage      文档页码（可空）
     * @param current      内容分页页码（可空）
     * @param size         每页条数（可空）
     * @return 环节内容视图
     */
    public StageContentVO stageContent(Long fileResultId, String stage, Long taskId, Long docPage,
                                       Integer current, Integer size) {
        return stageContent(fileResultId, stage, taskId, docPage, current, size, null, null);
    }

    /**
     * 产物内容查询：按环节取该次运行产物的内容条目（分页 + 状态/文档页/切片过滤）。
     *
     * <p>存储类读取失败（40454 / 40455）向上抛出；其余读取失败记日志并留空条目，不阻断详情。
     *
     * @param fileResultId 文件结果 ID
     * @param stage        环节（FILE_CHAIN_STAGES 白名单）
     * @param taskId       任务 ID（可空，缺省取该环节最新任务）
     * @param docPage      文档页过滤（可空）
     * @param current      页码（可空，缺省第 1 页）
     * @param size         每页条数（可空，缺省上限）
     * @param status       处置状态过滤（可空）
     * @param chunkFilter  切片内容过滤（可空）
     * @return 产物内容分页
     */
    @Override
    public StageContentVO stageContent(Long fileResultId, String stage, Long taskId, Long docPage,
                                       Integer current, Integer size, String status,
                                       ChunkContentFilterDTO chunkFilter) {
        accessGuard.requireExisting(fileResultId);
        ThrowUtil.throwIf(StrUtil.isBlank(stage) || !PipelineStage.FILE_CHAIN_STAGES.contains(stage),
                ErrorCode.PARAM_INVALID, "未知环节: " + stage);
        WbPipelineTask task = detailSupport.resolveTask(fileResultId, stageOf(stage), taskId, stageLabel(stage));

        int pageNo = StageContentPaginationSupport.resolveCurrent(current);
        int pageSize = StageContentPaginationSupport.resolveSize(size);
        StageContentVO vo = new StageContentVO();
        vo.setFileResultId(fileResultId);
        vo.setStage(stage);
        vo.setLatest(false);
        vo.setDocPage(NullUtil.isNull(docPage) || docPage < 1 ? null : docPage.intValue());
        vo.setItems(new ArrayList<>());
        vo.setTotal(0);
        vo.setCurrent(pageNo);
        vo.setSize(pageSize);
        vo.setTruncated(false);
        if (NullUtil.isNull(task)) {
            return vo;
        }
        vo.setTaskId(task.getId());
        // latest = 该次运行产物内容是否可用：按 task.productId 精确取产物（历史任务同样可展示自己的内容）
        WbPipelineProduct product = NullUtil.isNull(task) || NullUtil.isNull(task.getProductId()) ? null
                : pipelineProductDbService.getById(task.getProductId());
        if (NullUtil.isNull(product) || StrUtil.isBlank(product.getArtifactId())) {
            return vo;
        }
        vo.setLatest(true);

        try {
            if (PipelineStage.CHUNK.name().equals(stage)) {
                // 切片：过滤与分页都下推到 wb_knowledge_chunk（切片集合可能很大），total 按过滤后的口径给
                ChunkStageContentSupport.fillPage(vo, ObjectRef.ofProduct(product), chunkFilter, pageNo, pageSize,
                        chunkSetDbService, chunkDbService);
            } else {
                // 页码与状态过滤都先于分页：total 与翻页都按过滤后的口径给
                List<StageContentItemVO> all = StageContentPaginationSupport.filterByStatus(
                        StageContentPaginationSupport.filterByDocPage(buildItems(stage, product), docPage), status);
                vo.setTotal(all.size());
                vo.setItems(StageContentPaginationSupport.slice(all, pageNo, pageSize));
            }
        } catch (Exception e) {
            KnowledgeException storageFailure = StageArtifactReader.storageFailureOf(e);
            if (NullUtil.isNotNull(storageFailure)) {
                throw storageFailure;
            }
            log.warn("产物内容读取失败, fileResultId={}, stage={}, artifactId={}",
                    fileResultId, stage, product.getArtifactId(), e);
            vo.setItems(new ArrayList<>());
            vo.setTotal(0);
        }
        // 截断 = 这一页之后还有内容（含页码越界）
        vo.setTruncated(StageContentPaginationSupport.hasMore(vo.getCurrent(), vo.getSize(),
                vo.getItems().size(), vo.getTotal()));
        return vo;
    }

    /** 按环节分派到各环节的内容支持类 */
    private List<StageContentItemVO> buildItems(String stage, WbPipelineProduct product) {
        ObjectRef ref = ObjectRef.ofProduct(product);
        return switch (stage) {
            case "PARSE" -> ParseStageContentSupport.items(fileStorage, ref);
            case "STRUCTURE" -> StructureStageContentSupport.items(fileStorage, ref);
            case "PREPROCESS" -> PreprocessStageContentSupport.items(fileStorage, ref);
            case "EMBED" -> EmbedStageContentSupport.items(embeddingSetDbService, embeddingRecordDbService, ref);
            default -> List.of();
        };
    }

    private PipelineStage stageOf(String stage) {
        return PipelineStage.valueOf(stage);
    }

    /** 环节中文标签：可识别的环节取枚举标签，未知环节回落原值 */
    private String stageLabel(String stage) {
        try {
            return stageOf(stage).getLabel();
        } catch (IllegalArgumentException e) {
            return stage;
        }
    }
}
