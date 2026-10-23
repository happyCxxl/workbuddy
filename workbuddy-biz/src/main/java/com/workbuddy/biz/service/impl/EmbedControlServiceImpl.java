package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.EmbedControlService;
import com.workbuddy.biz.service.db.WbEmbeddingRecordDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.db.WbPipelineStepLogDbService;
import com.workbuddy.common.convert.knowledge.EmbedConverter;
import com.workbuddy.biz.service.support.stats.EmbedStatsSupport;
import com.workbuddy.biz.service.support.common.StageStrategySupport;
import com.workbuddy.biz.service.support.access.FileResultAccessGuard;
import com.workbuddy.biz.service.support.common.TaskDetailSupport;
import com.workbuddy.biz.task.support.TaskTriggerSupport;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.common.domain.entity.WbEmbeddingRecord;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.dto.response.embed.EmbedDetailVO;
import com.workbuddy.common.dto.response.embed.EmbedTriggerVO;
import com.workbuddy.common.dto.response.task.StageTriggerVO;
import com.workbuddy.common.dto.response.task.StepLogVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.chunking.ChunkProperties;
import com.workbuddy.worker.chunking.strategy.ChunkStrategy;
import com.workbuddy.worker.chunking.strategy.ChunkStrategyParser;
import com.workbuddy.worker.embedding.EmbedProperties;
import com.workbuddy.worker.embedding.EmbedWindowRules;
import com.workbuddy.worker.embedding.strategy.EmbedStrategy;
import com.workbuddy.worker.embedding.strategy.EmbedStrategyParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 向量化控制面服务实现（手动逐环节，与切片/预处理控制面同构）：
 * 策略解析两档：显式指定（校验存在/类型/启用，任一不满足 40433）→ 知识库绑定；两档都不命中一律 40438。
 * 上游 CHUNK 产物校验（必填，未指定或传 0 报 40436；产出任务未成功报 40439）→ 窗口前置校验（不兜底，触发时明确报错）→
 * 执行键查重（已有任务一律拒绝 40431）→ 建任务入队。QUEUED 补投归启动补偿与低频扫描。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbedControlServiceImpl implements EmbedControlService {

    private final WbPipelineProductDbService pipelineProductDbService;
    private final WbPipelineStepLogDbService stepLogDbService;
    private final WbEmbeddingSetDbService embeddingSetDbService;
    private final WbEmbeddingRecordDbService embeddingRecordDbService;
    private final EmbedConverter voConverter;
    private final EmbedStrategyParser strategyParser;
    private final ChunkStrategyParser chunkStrategyParser;
    private final EmbedProperties embedProperties;
    private final ChunkProperties chunkProperties;
    private final StageStrategySupport strategySupport;
    private final TaskTriggerSupport triggerSupport;
    private final TaskDetailSupport detailSupport;
    private final FileResultAccessGuard accessGuard;
    private final UpstreamProductGate upstreamProductGate;

    /**
     * 触发向量化：策略解析 → 上游切片产物校验 → 执行键查重 → 新建 EMBED 任务入队。
     *
     * @param fileResultId      文件结果 ID
     * @param strategyVersionId 策略版本行 ID（可空，不传用知识库绑定）
     * @param upstreamProductId 上游切片产物 ID（必填）
     * @return 触发响应（任务 ID + 生效策略版本）
     */
    @Override
    public EmbedTriggerVO embed(Long fileResultId, Long strategyVersionId, Long upstreamProductId) {
        WbFileResult fileResult = accessGuard.requireExisting(fileResultId);
        WbPipelineStrategyVersion strategyRow = resolveStrategyRow(fileResult, strategyVersionId);
        EmbedStrategy strategy = toStrategy(strategyRow);

        // 上游切片产物：必须显式指定
        WbPipelineProduct chunkProduct = requireChunkProduct(fileResultId, upstreamProductId);

        // 窗口前置校验：切片策略最大片长 ≤ 模型窗口（不兼容即报错，不做兜底）
        precheckWindow(chunkProduct, strategy);

        StageTriggerVO triggered = triggerSupport.trigger(fileResultId, PipelineStage.EMBED,
                chunkProduct.getId(), strategyRow.getId(), JsonUtil.toJsonStr(strategy),
                PipelineStage.EMBED.getLabel());
        return new EmbedTriggerVO(fileResultId, triggered.getPipelineTaskId(), strategy.fullVersion());
    }

    /**
     * 向量化详情：任务状态、子步骤、向量统计与记录、产物引用。
     *
     * @param fileResultId 文件结果 ID
     * @param taskId       任务 ID（可空表示取最新）
     * @return 向量化详情
     */
    @Override
    public EmbedDetailVO embedDetail(Long fileResultId, Long taskId) {
        accessGuard.requireExisting(fileResultId);
        WbPipelineTask task = detailSupport.resolveTask(fileResultId, PipelineStage.EMBED, taskId,
                PipelineStage.EMBED.getLabel());

        EmbedDetailVO vo = new EmbedDetailVO();
        vo.setFileResultId(fileResultId);
        vo.setRecords(new ArrayList<>());
        if (NullUtil.isNotNull(task)) {
            vo.setTaskId(task.getId());
            vo.setStage(task.getStage());
            vo.setTaskStatus(task.getStatus());
            vo.setErrorCode(task.getErrorCode());
            vo.setErrorMsg(task.getErrorMsg());
            vo.setStartedAt(task.getStartedAt());
            vo.setFinishedAt(task.getFinishedAt());
            vo.setSteps(stepLogDbService.listByTaskId(task.getId()).stream().map(StepLogVO::of).toList());
        }

        // 集合摘要/记录：按 task.productId → 产物 → artifactId 精确取该次运行的集合（历史任务同样可展示自己的集合；无任务/无产物留空）
        WbPipelineProduct product = NullUtil.isNull(task) || NullUtil.isNull(task.getProductId()) ? null
                : pipelineProductDbService.getById(task.getProductId());
        if (NullUtil.isNotNull(product)) {
            WbEmbeddingSet embeddingSet = embeddingSetDbService.getByArtifactId(product.getArtifactId());
            if (NullUtil.isNotNull(embeddingSet)) {
                List<WbEmbeddingRecord> records = embeddingRecordDbService.listByEmbeddingSetId(embeddingSet.getId());
                vo.setSummary(EmbedStatsSupport.summaryOf(embeddingSet, records));
                vo.setRecords(voConverter.toRecordItemVOs(records));
            }
            vo.setArtifactId(product.getArtifactId());
            vo.setContentHash(product.getContentHash());
            vo.setCapabilitySnapshot(product.getCapabilitySnapshot());
        }
        return vo;
    }

    /** 窗口前置校验（触发时明确报错；不可推算时跳过，任务期告警） */
    private void precheckWindow(WbPipelineProduct chunkProduct, EmbedStrategy strategy) {
        ChunkStrategy chunkStrategy = resolveChunkStrategy(chunkProduct.getCapabilitySnapshot());
        if (NullUtil.isNull(chunkStrategy)) {
            return;
        }
        EmbedWindowRules.WindowBound window = EmbedWindowRules.resolve(
                chunkStrategy, chunkProperties, strategy, embedProperties);
        if (!window.computable()) {
            // 兜底 none 不可推算：跳过（评测专用口径）
            return;
        }
        ThrowUtil.throwIf(window.bound() > window.allowed(), ErrorCode.EMBED_MODEL_INCOMPATIBLE,
                "切片策略最大片长 " + window.bound() + " 字符超过模型窗口 " + window.allowed() + " 字符，请更换模型或调整切片策略");
    }

    /** 上游切片产物校验：必须显式指定，校验存在/环节/归属；未指定直接拒绝（无按时间猜的兜底）。 */
    private WbPipelineProduct requireChunkProduct(Long fileResultId, Long productId) {
        ThrowUtil.throwIf(NullUtil.isNull(productId) || productId == 0L, ErrorCode.TASK_UPSTREAM_MISSING,
                "未指定上游切片产物，请从切片产物的卡片发起触发");
        WbPipelineProduct product = pipelineProductDbService.getById(productId);
        ThrowUtil.throwIf(NullUtil.isNull(product), ErrorCode.FILE_RESULT_NOT_FOUND, "指定上游产物不存在");
        ThrowUtil.throwIf(!PipelineStage.CHUNK.name().equals(product.getStage()), ErrorCode.FILE_RESULT_NOT_FOUND,
                "指定产物环节不匹配：期望 CHUNK");
        ThrowUtil.throwIf(!fileResultId.equals(product.getFileResultId()), ErrorCode.FILE_RESULT_NOT_FOUND,
                "指定产物不属于该文件结果");
        // 下游门禁：产出任务已成功或部分成功才放行（未成功的运行留下的产物不再被消费）
        upstreamProductGate.requireConsumable(product);
        return product;
    }

    /**
     * 上游切片产物的策略快照 → 切片策略对象（窗口兼容校验据此推算最大片长）。
     *
     * <p>快照缺失或损坏返回 null（不构造默认策略）：触发时跳过窗口兼容校验，不阻断本次触发。
     *
     * @param snapshot 切片产物行上的策略快照 JSON 文本，可空
     * @return 补全后的切片策略；缺失或损坏返回 null
     */
    private ChunkStrategy resolveChunkStrategy(String snapshot) {
        if (StrUtil.isBlank(snapshot)) {
            return null;
        }
        try {
            return chunkStrategyParser.parse(snapshot);
        } catch (Exception e) {
            log.warn("向量化触发上游切片策略快照缺失或损坏，跳过窗口兼容校验", e);
            return null;
        }
    }

    /** 策略解析两档：显式指定（40433 校验存在/类型/启用）→ 知识库绑定；两档都不命中一律拒绝 40438。 */
    private WbPipelineStrategyVersion resolveStrategyRow(WbFileResult fileResult, Long strategyVersionId) {
        return strategySupport.resolve(
                fileResult, strategyVersionId, EmbedStrategy.TYPE, "EmbedControlServiceImpl 向量化");
    }

    private EmbedStrategy toStrategy(WbPipelineStrategyVersion row) {
        return strategySupport.bindRow(row, strategyParser::parse);
    }
}
