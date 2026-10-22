package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.PreprocessControlService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.support.stats.PreprocessStatsSupport;
import com.workbuddy.common.convert.KnowledgeConverter;
import com.workbuddy.biz.service.support.common.StageStrategySupport;
import com.workbuddy.biz.service.support.stats.StatsSupport;
import com.workbuddy.biz.service.support.access.FileResultAccessGuard;
import com.workbuddy.biz.service.support.common.TaskDetailSupport;
import com.workbuddy.biz.task.support.TaskTriggerSupport;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.preprocess.PreprocessView;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.preprocess.PreprocessDetailVO;
import com.workbuddy.common.dto.response.preprocess.PreprocessTriggerVO;
import com.workbuddy.common.dto.response.task.StageTriggerVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.WorkbuddyException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategyParser;
import com.workbuddy.common.dto.response.preprocess.PreprocessStatsVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * 预处理控制面服务实现（手动逐环节）：
 * 策略解析两档：显式指定（校验存在/类型/启用，任一不满足 40433）→ 知识库绑定（绑定开关开启 + 绑定行存在 + 绑定行启用）；
 * 两档都不命中一律 40438。上游产物校验（必填，未指定或传 0 报 40436；产出任务未成功报 40439）→ 执行键查重（已有任务一律拒绝 40431）→ 建任务入队。
 * 策略快照写 task.strategy_snapshot（触发时固定），执行只用快照。QUEUED 补投归启动补偿与低频扫描。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreprocessControlServiceImpl implements PreprocessControlService {

    private final WbPipelineProductDbService pipelineProductDbService;
    private final StageStrategySupport strategySupport;
    private final TaskTriggerSupport triggerSupport;
    private final TaskDetailSupport detailSupport;
    private final FileStorage fileStorage;
    private final PreprocessStrategyParser strategyParser;
    private final KnowledgeConverter voConverter;
    private final FileResultAccessGuard accessGuard;
    private final UpstreamProductGate upstreamProductGate;

    /**
     * 触发预处理（手动逐环节，重跑同入口）：策略解析两档 → 上游组装产物校验（必填）→ 执行键查重（已有任务一律拒绝 40431）→ 新建 PREPROCESS 任务入队。
     *
     * @param fileResultId      文件结果 ID
     * @param strategyVersionId 策略版本行 ID（可选；不传用知识库绑定，两档都不命中报 40438）
     * @param upstreamProductId 上游组装产物 ID（必填，未指定或传 0 报 40436）
     * @return 触发响应（任务 ID + 生效策略版本）
     */
    @Override
    public PreprocessTriggerVO preprocess(Long fileResultId, Long strategyVersionId, Long upstreamProductId) {
        WbFileResult fileResult = accessGuard.requireExisting(fileResultId);
        WbPipelineStrategyVersion strategyRow = resolveStrategyRow(fileResult, strategyVersionId);
        PreprocessStrategy strategy = toStrategy(strategyRow);

        // 上游组装产物：必须显式指定
        WbPipelineProduct structureProduct = requireStructureProduct(fileResultId, upstreamProductId);

        StageTriggerVO triggered = triggerSupport.trigger(fileResultId, PipelineStage.PREPROCESS,
                structureProduct.getId(), strategyRow.getId(), JsonUtil.toJsonStr(strategy),
                PipelineStage.PREPROCESS.getLabel());
        return new PreprocessTriggerVO(fileResultId, triggered.getPipelineTaskId(), strategy.fullVersion());
    }

    /**
     * 预处理详情：任务状态、子步骤、字段与剔除统计、产物引用。
     *
     * @param fileResultId 文件结果 ID
     * @param taskId       任务 ID（可空表示取最新）
     * @return 预处理详情
     */
    @Override
    public PreprocessDetailVO preprocessDetail(Long fileResultId, Long taskId) {
        accessGuard.requireExisting(fileResultId);
        WbPipelineTask task = detailSupport.resolveTask(fileResultId, PipelineStage.PREPROCESS, taskId,
                PipelineStage.PREPROCESS.getLabel());

        PreprocessDetailVO vo = new PreprocessDetailVO();
        detailSupport.withTask(vo, fileResultId, task);

        // 产物引用/统计/视图元素：按 task.productId 精确取该次运行的产物（历史任务同样可展示自己的产物；无任务/无产物留空）
        vo.setElements(new ArrayList<>());
        PreprocessView view = null;
        WbPipelineProduct product = detailSupport.productOfTask(task);
        if (NullUtil.isNotNull(product)) {
            detailSupport.withProductRef(vo, product);
            view = readView(product, vo);
        }
        // 统计与摘要读产物现算：与执行树预处理节点同一份口径，不落产物；产物读不到时不陈述结论
        PreprocessStatsVO stageStats = PreprocessStatsSupport.stats(vo.getStartedAt(), vo.getFinishedAt(), view);
        vo.setStageStats(stageStats);
        vo.setStageSummary(PreprocessStatsSupport.summary(vo.getErrorMsg(), stageStats, vo.getStatus()));
        return vo;
    }

    /**
     * 读派生视图产物并委托 VO 组装器提取统计/视图元素。
     *
     * <p>存储类读取失败（40454 / 40455）向上抛出；其余读取失败记日志并留空，不阻断详情。
     *
     * @return 产物本体；读不到返回 null（统计与摘要随之为空）
     */
    private PreprocessView readView(WbPipelineProduct product, PreprocessDetailVO vo) {
        try {
            byte[] content = fileStorage.getObject(ObjectRef.ofProduct(product));
            PreprocessView view = JsonUtil.toObject(
                    new String(content, StandardCharsets.UTF_8), PreprocessView.class);
            if (NullUtil.isNull(view)) {
                log.debug("预处理视图产物反序列化为空, artifactId={}", product.getArtifactId());
                return null;
            }
            vo.setSummary(PreprocessStatsSupport.summaryOf(view));
            vo.setElements(voConverter.toElementVOs(view));
            return view;
        } catch (Exception e) {
            WorkbuddyException storageFailure = StatsSupport.storageFailureOf(e);
            if (NullUtil.isNotNull(storageFailure)) {
                throw storageFailure;
            }
            log.warn("读取预处理视图产物失败, artifactId={}", product.getArtifactId(), e);
            return null;
        }
    }

    /** 策略解析两档：显式指定（40433 校验存在/类型/启用）→ 知识库绑定；两档都不命中一律拒绝 40438。 */
    private WbPipelineStrategyVersion resolveStrategyRow(WbFileResult fileResult, Long strategyVersionId) {
        return strategySupport.resolve(
                fileResult, strategyVersionId, PreprocessStrategy.TYPE, "PreprocessControlServiceImpl 预处理");
    }

    /** 上游组装产物校验：必须显式指定，校验存在/环节/归属；未指定直接拒绝（无按时间猜的兜底）。 */
    private WbPipelineProduct requireStructureProduct(Long fileResultId, Long productId) {
        ThrowUtil.throwIf(NullUtil.isNull(productId) || productId == 0L, ErrorCode.TASK_UPSTREAM_MISSING,
                "未指定上游统一结构产物，请从组装产物的卡片发起触发");
        WbPipelineProduct product = pipelineProductDbService.getById(productId);
        ThrowUtil.throwIf(NullUtil.isNull(product), ErrorCode.FILE_RESULT_NOT_FOUND, "指定上游产物不存在");
        ThrowUtil.throwIf(!PipelineStage.STRUCTURE.name().equals(product.getStage()), ErrorCode.FILE_RESULT_NOT_FOUND,
                "指定产物环节不匹配：期望 STRUCTURE");
        ThrowUtil.throwIf(!fileResultId.equals(product.getFileResultId()), ErrorCode.FILE_RESULT_NOT_FOUND,
                "指定产物不属于该文件结果");
        // 下游门禁：产出任务已成功或部分成功才放行（未成功的运行留下的产物不再被消费）
        upstreamProductGate.requireConsumable(product);
        return product;
    }

    private PreprocessStrategy toStrategy(WbPipelineStrategyVersion row) {
        // configSnapshot 为 rules/custom 结构（无 name/version），解析补全默认后回填行信息；快照损坏按 40433 拒绝
        return strategySupport.bindRow(row, strategyParser::parseConfig);
    }
}
