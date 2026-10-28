package com.workbuddy.biz.service.impl;

import com.workbuddy.biz.service.ParseControlService;
import com.workbuddy.biz.service.support.access.FileResultAccessGuard;
import com.workbuddy.biz.service.support.stats.ParseStatsSupport;
import com.workbuddy.biz.service.support.task.TaskDetailSupport;
import com.workbuddy.biz.task.support.TaskTriggerSupport;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.domain.entity.WbPipelineTask;
import com.workbuddy.common.domain.parse.ParseResult;
import com.workbuddy.common.domain.parse.QualityWarning;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.dto.response.parse.ParseDetailVO;
import com.workbuddy.common.dto.response.parse.ParseWarningVO;
import com.workbuddy.common.dto.response.task.StageTriggerVO;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析控制面服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParseControlServiceImpl implements ParseControlService {

    /** 告警文案里的页码：page 2 / 第 3 页 / 坏页 4 */
    private static final Pattern WARNING_PAGE = Pattern.compile("(?:page|第|坏页)\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);

    private final TaskTriggerSupport triggerSupport;
    private final TaskDetailSupport detailSupport;
    private final FileStorage fileStorage;
    private final FileResultAccessGuard accessGuard;

    /**
     * 触发解析（手动逐环节，链路起点）：解析没有上游产物、也没有策略版本，
     * 执行键即「文件结果」——一份文件结果只解析一次。
     *
     * <p>已有任务（任何状态，含执行中）一律拒绝 40431；没有才新建 PARSE 任务（QUEUED）落库并入队。
     * 完成后停在终态，不自动触发组装；需要重新解析请重新提交文件（产生新的文件结果）。
     *
     * @param fileResultId 文件结果 ID
     * @return 触发响应（新建任务 ID）
     */
    @Override
    public StageTriggerVO parse(Long fileResultId) {
        accessGuard.requireExisting(fileResultId);
        return triggerSupport.trigger(fileResultId, PipelineStage.PARSE, null, null, null,
                PipelineStage.PARSE.getLabel());
    }

    /**
     * 解析详情：任务状态、子步骤、解析统计与产物引用。
     *
     * @param fileResultId 文件结果 ID
     * @param taskId       任务 ID（可空表示取最新）
     * @return 解析详情
     */
    @Override
    public ParseDetailVO parseDetail(Long fileResultId, Long taskId) {
        accessGuard.requireExisting(fileResultId);
        WbPipelineTask task = detailSupport.resolveTask(fileResultId, PipelineStage.PARSE, taskId,
                PipelineStage.PARSE.getLabel());

        ParseDetailVO vo = new ParseDetailVO();
        detailSupport.withTask(vo, fileResultId, task);

        // 产物引用/告警/统计：按 task.productId 精确取该次运行的产物（历史任务同样可展示自己的产物；无任务/无产物留空）
        // 解析的统计结构固定（LineageParseStatsVO），走 parseStats；通用 stageStats 只给没有专属结构的环节
        vo.setWarnings(new ArrayList<>());
        vo.setWarningItems(new ArrayList<>());
        WbPipelineProduct product = detailSupport.productOfTask(task);
        if (NullUtil.isNotNull(product)) {
            detailSupport.withProductRef(vo, product);
            ParseResult parseResult = ParseStatsSupport.readArtifact(fileStorage, ObjectRef.ofProduct(product));
            vo.setWarnings(warningsOf(parseResult));
            vo.setWarningItems(warningItemsOf(parseResult));
            vo.setParseStats(ParseStatsSupport.stats(vo.getStartedAt(), vo.getFinishedAt(), parseResult));
            vo.setParseSummary(ParseStatsSupport.summary(vo.getErrorMsg(), vo.getParseStats(), vo.getStatus()));
        }
        return vo;
    }

    /** 产物本体 → 告警文案（展示用；无产物或无告警返回空列表） */
    private List<String> warningsOf(ParseResult parseResult) {
        if (NullUtil.isNull(parseResult) || NullUtil.isNull(parseResult.getQuality())) {
            return new ArrayList<>();
        }
        return parseResult.getQuality().getWarnings().stream()
                .map(w -> w.getLevel() + " " + w.getCode() + ": " + w.getMessage())
                .toList();
    }

    /** 产物本体 → 结构化告警：页码从说明中解析，解析不到时为空 */
    private List<ParseWarningVO> warningItemsOf(ParseResult parseResult) {
        if (NullUtil.isNull(parseResult) || NullUtil.isNull(parseResult.getQuality())) {
            return new ArrayList<>();
        }
        List<ParseWarningVO> items = new ArrayList<>();
        for (QualityWarning warning : parseResult.getQuality().getWarnings()) {
            ParseWarningVO item = new ParseWarningVO();
            item.setCode(warning.getCode());
            item.setLevel(warning.getLevel());
            item.setMessage(warning.getMessage());
            item.setElementId(warning.getElementId());
            item.setPage(warningPage(warning.getMessage()));
            items.add(item);
        }
        return items;
    }

    /** 从告警说明里解析第一个页码 */
    private Integer warningPage(String message) {
        if (NullUtil.isNull(message)) {
            return null;
        }
        Matcher matcher = WARNING_PAGE.matcher(message);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }
}
