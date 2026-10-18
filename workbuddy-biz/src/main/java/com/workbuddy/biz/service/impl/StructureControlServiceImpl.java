package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.StructureControlService;
import com.workbuddy.biz.service.db.WbPipelineProductDbService;
import com.workbuddy.biz.service.support.FileResultAccessGuard;
import com.workbuddy.biz.service.support.StatsSupport;
import com.workbuddy.biz.service.support.StructureStatsSupport;
import com.workbuddy.biz.service.support.StructureVoAssembler;
import com.workbuddy.biz.service.support.TaskDetailSupport;
import com.workbuddy.biz.task.support.TaskTriggerSupport;
import com.workbuddy.biz.task.UpstreamProductGate;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.domain.structure.UnifiedDocument;
import com.workbuddy.common.dto.response.structure.StructureDetailVO;
import com.workbuddy.common.dto.response.task.StageTriggerVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

/**
 * 结构组装控制面服务实现（手动逐环节）：
 * 上游产物校验（必填，未指定或传 0 报 40436；产出任务未成功报 40439）→ 执行键查重（已有任务一律拒绝 40431）→
 * 新建 STRUCTURE 任务（upstream=指定 PARSE 产物）入队；QUEUED 补投归启动补偿与低频扫描。
 * 组装详情：任务状态 + 子步骤 + 统一文档产物推导的统计/告警/冲突/文档内容大纲。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StructureControlServiceImpl implements StructureControlService {

    private final WbPipelineProductDbService pipelineProductDbService;
    private final TaskTriggerSupport triggerSupport;
    private final TaskDetailSupport detailSupport;
    private final FileStorage fileStorage;
    private final StructureVoAssembler voAssembler;
    private final FileResultAccessGuard accessGuard;
    private final UpstreamProductGate upstreamProductGate;

    /**
     * 触发组装（手动逐环节）：上游解析产物校验（必填，未指定或传 0 报 40436）→ 执行键查重（已有任务一律拒绝 40431）→ 新建 STRUCTURE 任务入队。
     *
     * @param fileResultId      文件结果 ID
     * @param upstreamProductId 上游解析产物 ID（必填，须显式指定）
     * @return 触发响应（新建任务 ID）
     */
    @Override
    public StageTriggerVO structure(Long fileResultId, Long upstreamProductId) {
        accessGuard.requireExisting(fileResultId);
        // 上游解析产物：必须显式指定，校验存在/环节/归属；未指定直接拒绝（无按时间猜的兜底）。
        WbPipelineProduct parseProduct = requireParseProduct(fileResultId, upstreamProductId);
        return triggerSupport.trigger(fileResultId, PipelineStage.STRUCTURE, parseProduct.getId(), null, null,
                PipelineStage.STRUCTURE.getLabel());
    }

    @Override
    public StructureDetailVO structureDetail(Long fileResultId, Long taskId) {
        accessGuard.requireExisting(fileResultId);
        WbPipelineTask task = detailSupport.resolveTask(fileResultId, PipelineStage.STRUCTURE, taskId,
                PipelineStage.STRUCTURE.getLabel());

        StructureDetailVO vo = new StructureDetailVO();
        detailSupport.withTask(vo, fileResultId, task);

        // 产物引用/统计/冲突/文档内容：按 task.productId 精确取该次运行的产物（历史任务同样可展示自己的产物；无任务/无产物留空）
        vo.setWarnings(new ArrayList<>());
        vo.setConflicts(new ArrayList<>());
        vo.setOutline(new ArrayList<>());
        UnifiedDocument document = null;
        WbPipelineProduct product = detailSupport.productOfTask(task);
        if (NullUtil.isNotNull(product)) {
            detailSupport.withProductRef(vo, product);
            document = readDocument(product, vo);
        }
        // 统计与摘要读产物现算：与执行树组装节点同一份口径，不落产物；产物读不到时不陈述结论
        Map<String, Object> stageStats =
                StructureStatsSupport.stats(vo.getStartedAt(), vo.getFinishedAt(), document);
        vo.setStageStats(stageStats);
        vo.setStageSummary(StructureStatsSupport.summary(vo.getErrorMsg(), stageStats, vo.getStatus()));
        return vo;
    }

    /** 上游解析产物校验：必须显式指定，校验存在/环节/归属；未指定直接拒绝（无按时间猜的兜底）。 */
    private WbPipelineProduct requireParseProduct(Long fileResultId, Long productId) {
        ThrowUtil.throwIf(NullUtil.isNull(productId) || productId == 0L, ErrorCode.TASK_UPSTREAM_MISSING,
                "未指定上游解析产物，请从解析产物的卡片发起触发");
        WbPipelineProduct product = pipelineProductDbService.getById(productId);
        ThrowUtil.throwIf(NullUtil.isNull(product), ErrorCode.FILE_RESULT_NOT_FOUND, "指定上游产物不存在");
        ThrowUtil.throwIf(!PipelineStage.PARSE.name().equals(product.getStage()), ErrorCode.FILE_RESULT_NOT_FOUND,
                "指定产物环节不匹配：期望 PARSE");
        ThrowUtil.throwIf(!fileResultId.equals(product.getFileResultId()), ErrorCode.FILE_RESULT_NOT_FOUND,
                "指定产物不属于该文件结果");
        // 下游门禁：产出任务已成功或部分成功才放行（未成功的运行留下的产物不再被消费）
        upstreamProductGate.requireConsumable(product);
        return product;
    }

    /**
     * 读统一文档产物并委托 VO 组装器提取统计/告警/冲突/文档内容大纲。
     *
     * <p>存储类读取失败（40454 / 40455）向上抛出；其余读取失败记日志并留空，不阻断详情。
     *
     * @return 产物本体；读不到返回 null
     */
    private UnifiedDocument readDocument(WbPipelineProduct product, StructureDetailVO vo) {
        try {
            byte[] content = fileStorage.getObject(ObjectRef.ofProduct(product));
            UnifiedDocument document = JsonUtil.toObject(
                    new String(content, StandardCharsets.UTF_8), UnifiedDocument.class);
            if (NullUtil.isNull(document)) {
                return null;
            }
            vo.setSummary(voAssembler.toSummary(document));
            vo.setWarnings(voAssembler.toWarningTexts(document.getQuality()));
            vo.setConflicts(voAssembler.toConflicts(document.getQuality()));
            vo.setOutline(voAssembler.toOutline(document));
            return document;
        } catch (Exception e) {
            KnowledgeException storageFailure = StatsSupport.storageFailureOf(e);
            if (NullUtil.isNotNull(storageFailure)) {
                throw storageFailure;
            }
            log.warn("读取统一文档产物失败, artifactId={}", product.getArtifactId(), e);
            return null;
        }
    }
}
