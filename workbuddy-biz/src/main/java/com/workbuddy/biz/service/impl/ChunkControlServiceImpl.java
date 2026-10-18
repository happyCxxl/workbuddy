package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.ChunkControlService;
import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.support.ChunkStatsSupport;
import com.workbuddy.biz.service.support.ChunkVoAssembler;
import com.workbuddy.biz.service.support.PreprocessStatsSupport;
import com.workbuddy.biz.service.support.StageStrategySupport;
import com.workbuddy.biz.service.support.FileResultAccessGuard;
import com.workbuddy.biz.service.support.TaskDetailSupport;
import com.workbuddy.biz.task.support.TaskTriggerSupport;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.chunk.ChunkSet;
import com.workbuddy.common.domain.entity.WbChunk;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.chunk.ChunkDetailVO;
import com.workbuddy.common.dto.response.chunk.ChunkTriggerVO;
import com.workbuddy.common.dto.response.task.StageTriggerVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 切片控制面服务实现（手动逐环节）：
 * 策略解析两档：显式指定（校验存在/类型/启用，任一不满足 40433）→ 知识库绑定；两档都不命中一律 40438。
 * 上游产物校验（必填，未指定或传 0 报 40436；产出任务未成功报 40439）→ 执行键查重（已有任务一律拒绝 40431）→ 建任务入队。
 * 策略快照写 task.strategy_snapshot（触发时固定），执行只用快照。QUEUED 补投归启动补偿与低频扫描。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkControlServiceImpl implements ChunkControlService {

    private final WbPipelineProductDbService pipelineProductDbService;
    private final StageStrategySupport strategySupport;
    private final TaskTriggerSupport triggerSupport;
    private final TaskDetailSupport detailSupport;
    private final WbChunkSetDbService chunkSetDbService;
    private final WbChunkDbService chunkDbService;
    private final ChunkStrategyParser strategyParser;
    private final ChunkVoAssembler voAssembler;
    private final FileStorage fileStorage;
    private final FileResultAccessGuard accessGuard;
    private final UpstreamProductGate upstreamProductGate;

    /**
     * 触发切片（手动逐环节）：策略解析 → 上游预处理视图产物校验 → 执行键查重（已有任务一律拒绝 40431）→ 新建 CHUNK 任务入队。
     *
     * @param fileResultId      文件结果 ID
     * @param strategyVersionId 策略版本行 ID（可选；不传则用知识库绑定，两档都没有报 40438）
     * @param upstreamProductId 上游预处理产物 ID（必填，未指定报 40436）
     * @return 触发响应（任务 ID + 生效策略版本）
     */
    @Override
    public ChunkTriggerVO chunk(Long fileResultId, Long strategyVersionId, Long upstreamProductId) {
        WbFileResult fileResult = accessGuard.requireExisting(fileResultId);
        WbPipelineStrategyVersion strategyRow = resolveStrategyRow(fileResult, strategyVersionId);
        ChunkStrategy strategy = toStrategy(strategyRow);

        // 上游预处理产物：必须显式指定
        WbPipelineProduct preprocessProduct = requirePreprocessProduct(fileResultId, upstreamProductId);

        StageTriggerVO triggered = triggerSupport.trigger(fileResultId, PipelineStage.CHUNK,
                preprocessProduct.getId(), strategyRow.getId(), JsonUtil.toJsonStr(strategy),
                PipelineStage.CHUNK.getLabel());
        return new ChunkTriggerVO(fileResultId, triggered.getPipelineTaskId(), strategy.fullVersion());
    }

    @Override
    public ChunkDetailVO chunkDetail(Long fileResultId, Long taskId) {
        accessGuard.requireExisting(fileResultId);
        WbPipelineTask task = detailSupport.resolveTask(fileResultId, PipelineStage.CHUNK, taskId,
                PipelineStage.CHUNK.getLabel());

        ChunkDetailVO vo = new ChunkDetailVO();
        detailSupport.withTask(vo, fileResultId, task);

        // 切片集合/切片列表：按 task.productId → 产物 → artifactId 精确取该次运行的集合（历史任务同样可展示自己的集合；无任务/无产物留空）
        vo.setChunks(new ArrayList<>());
        ChunkSet chunkSet = null;
        WbPipelineProduct product = detailSupport.productOfTask(task);
        if (NullUtil.isNotNull(product)) {
            // 产物侧现读一次：统计与来源元素个数 / 兜底原因都只在这份产物里（wb_knowledge_chunk 无对应列）
            chunkSet = ChunkStatsSupport.readChunkSet(fileStorage, ObjectRef.ofProduct(product));
            WbChunkSet chunkSetRow = chunkSetDbService.getByArtifactId(product.getArtifactId());
            if (NullUtil.isNotNull(chunkSetRow)) {
                List<WbChunk> chunks = chunkDbService.listByChunkSetId(chunkSetRow.getId());
                vo.setSummary(voAssembler.toSummary(chunkSetRow, chunks));
                vo.setChunks(voAssembler.toChunkItemVOs(chunks, productChunks(chunkSet)));
            }
            detailSupport.withProductRef(vo, product);
        }
        // 统计与摘要读产物现算：与执行树切片节点同一份口径，不落产物；产物读不到时不陈述结论
        Map<String, Object> stageStats = ChunkStatsSupport.stats(vo.getStartedAt(), vo.getFinishedAt(), chunkSet,
                strategyOf(task), readUpstreamView(task));
        vo.setStageStats(stageStats);
        vo.setStageSummary(ChunkStatsSupport.summary(vo.getErrorMsg(), stageStats, vo.getStatus()));
        return vo;
    }

    /**
     * 任务快照 → 切片策略（片长上限与兜底参数从它取）。
     *
     * <p>无任务、快照缺失或快照损坏一律返回 null（不构造默认策略）：这几个策略口径整组留空，
     * 页面上如实表现为无策略快照，其余统计项照常下发。
     */
    private ChunkStrategy strategyOf(WbPipelineTask task) {
        if (NullUtil.isNull(task)) {
            return null;
        }
        try {
            return strategyParser.parse(task.getStrategySnapshot());
        } catch (Exception e) {
            log.warn("切片详情策略快照缺失或损坏，策略类口径留空, taskId={}", task.getId(), e);
            return null;
        }
    }

    /** 产物里的片按 chunkId 建索引（产物读不到时为空表） */
    private Map<String, Chunk> productChunks(ChunkSet chunkSet) {
        if (NullUtil.isNull(chunkSet) || NullUtil.isNull(chunkSet.getChunks())) {
            return Map.of();
        }
        return chunkSet.getChunks().stream()
                .filter(chunk -> StrUtil.isNotBlank(chunk.getChunkId()))
                .collect(Collectors.toMap(Chunk::getChunkId, chunk -> chunk, (first, second) -> first));
    }

    /** 读上游预处理视图（"进入切片 / 跳过"元素数的口径来源；取不到返回 null） */
    private PreprocessView readUpstreamView(WbPipelineTask task) {
        if (NullUtil.isNull(task) || NullUtil.isNull(task.getUpstreamProductId())) {
            return null;
        }
        WbPipelineProduct upstream = pipelineProductDbService.getById(task.getUpstreamProductId());
        return NullUtil.isNull(upstream)
                ? null : PreprocessStatsSupport.readView(fileStorage, ObjectRef.ofProduct(upstream));
    }

    /** 策略解析两档：显式指定（40433 校验存在/类型/启用）→ 知识库绑定；两档都不命中一律拒绝 40438。 */
    private WbPipelineStrategyVersion resolveStrategyRow(WbFileResult fileResult, Long strategyVersionId) {
        return strategySupport.resolve(
                fileResult, strategyVersionId, ChunkStrategy.TYPE, "ChunkControlServiceImpl 切片");
    }

    /** 上游预处理产物校验：必须显式指定，校验存在/环节/归属；未指定直接拒绝（无按时间猜的兜底）。 */
    private WbPipelineProduct requirePreprocessProduct(Long fileResultId, Long productId) {
        ThrowUtil.throwIf(NullUtil.isNull(productId) || productId == 0L, ErrorCode.TASK_UPSTREAM_MISSING,
                "未指定上游预处理视图产物，请从预处理产物的卡片发起触发");
        WbPipelineProduct product = pipelineProductDbService.getById(productId);
        ThrowUtil.throwIf(NullUtil.isNull(product), ErrorCode.FILE_RESULT_NOT_FOUND, "指定上游产物不存在");
        ThrowUtil.throwIf(!PipelineStage.PREPROCESS.name().equals(product.getStage()), ErrorCode.FILE_RESULT_NOT_FOUND,
                "指定产物环节不匹配：期望 PREPROCESS");
        ThrowUtil.throwIf(!fileResultId.equals(product.getFileResultId()), ErrorCode.FILE_RESULT_NOT_FOUND,
                "指定产物不属于该文件结果");
        // 下游门禁：产出任务已成功或部分成功才放行（未成功的运行留下的产物不再被消费）
        upstreamProductGate.requireConsumable(product);
        return product;
    }

    private ChunkStrategy toStrategy(WbPipelineStrategyVersion row) {
        // configSnapshot 为 routes/pipeline 结构（无 name/version），解析补全默认后回填行信息；快照损坏按 40433 拒绝
        return strategySupport.bindRow(row, strategyParser::parse);
    }
}
