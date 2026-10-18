package com.workbuddy.biz.service.support;

import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbStrategyBinding;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.domain.rules.KnowledgeBaseRules;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.common.error.ErrorCode;
import com.workbuddy.common.exception.KnowledgeException;
import com.workbuddy.common.exception.ThrowUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.StageStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * 环节策略解析共用助手：显式指定 → KB 绑定两档口径完全同构，集中在这里；
 * 两档都不命中一律拒绝（不做「取启用中最新」这类兜底，也不在代码里造默认策略）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StageStrategySupport {

    private final WbPipelineStrategyVersionDbService strategyVersionDbService;
    private final WbStrategyBindingDbService strategyBindingDbService;
    private final WbKnowledgeBaseDbService knowledgeBaseDbService;

    /**
     * 解析生效策略版本行：显式指定优先，否则取知识库绑定；两档都不命中（绑定开关关闭、未绑定、
     * 绑定行缺失或已停用）一律拒绝 40438，不做兜底。
     *
     * @param fileResult        文件结果（取所属知识库判定绑定开关）
     * @param strategyVersionId 显式指定的策略版本行 ID（可空）
     * @param type              策略类型（PREPROCESS / CHUNK / EMBED）
     * @param stageLabel        环节中文名（失败文案用）
     * @return 生效的策略版本行（非空）
     */
    public WbPipelineStrategyVersion resolve(WbFileResult fileResult, Long strategyVersionId,
                                             String type, String stageLabel) {
        if (NullUtil.isNotNull(strategyVersionId)) {
            return requireExplicit(strategyVersionId, type);
        }
        WbKnowledgeBase kb = knowledgeBaseDbService.getActiveById(fileResult.getKnowledgeBaseId());
        ThrowUtil.throwIf(!KnowledgeBaseRules.isStrategyBindingEnabled(kb), ErrorCode.TASK_STRATEGY_MISSING,
                stageLabel + "未绑定策略，请显式选择策略后触发");
        WbStrategyBinding binding = strategyBindingDbService
                .getByKbAndType(fileResult.getKnowledgeBaseId(), type);
        ThrowUtil.throwIf(NullUtil.isNull(binding), ErrorCode.TASK_STRATEGY_MISSING,
                stageLabel + "未绑定策略，请显式选择策略后触发");
        WbPipelineStrategyVersion bound = strategyVersionDbService.getById(binding.getStrategyVersionId());
        ThrowUtil.throwIf(NullUtil.isNull(bound) || !RowStatus.ACTIVE.name().equals(bound.getStatus()),
                ErrorCode.TASK_STRATEGY_MISSING, stageLabel + "绑定的策略已停用或不存在，请重新绑定或显式选择策略");
        return bound;
    }

    /**
     * 显式指定档：按行 id 精确引用，校验存在、类型匹配、处于启用（任一不满足报 40433）。
     *
     * @param strategyVersionId 策略版本行 ID（非空）
     * @param type              期望的策略类型
     */
    public WbPipelineStrategyVersion requireExplicit(Long strategyVersionId, String type) {
        WbPipelineStrategyVersion row = strategyVersionDbService.getById(strategyVersionId);
        ThrowUtil.throwIf(NullUtil.isNull(row), ErrorCode.STRATEGY_VERSION_NOT_FOUND);
        ThrowUtil.throwIf(!type.equals(row.getType()),
                ErrorCode.STRATEGY_VERSION_NOT_FOUND, "策略类型不匹配：期望 " + type);
        ThrowUtil.throwIf(!RowStatus.ACTIVE.name().equals(row.getStatus()),
                ErrorCode.STRATEGY_VERSION_NOT_FOUND, "策略已停用，请先启用后再触发");
        return row;
    }

    /**
     * 按策略版本行回填元信息：策略配置快照（configSnapshot）里没有 type/name/version 三项，
     * 统一在这里补齐，各环节解析器不必各自重复一遍 setter。
     *
     * @param strategy 环节策略实例（解析器已按快照构造）
     * @param row      策略版本行
     * @return 传入的策略实例（type/name/version 已回填）
     */
    public <T extends StageStrategy> T bindMeta(T strategy, WbPipelineStrategyVersion row) {
        strategy.setType(row.getType());
        strategy.setName(row.getName());
        strategy.setVersion(row.getVersion());
        return strategy;
    }

    /**
     * 策略版本行 → 策略对象：配置快照为空或损坏一律按「策略版本不可用」拒绝，
     * 不让解析异常冒成内部错误（触发期把数据问题按 40433 报给用户）。
     *
     * @param row   策略版本行（取 configSnapshot 与元信息）
     * @param parse 该环节的配置快照解析器（切片/向量化用 parse，预处理用 parseConfig）
     * @return 生效的策略对象（元信息已按行回填）
     */
    public <T extends StageStrategy> T bindRow(WbPipelineStrategyVersion row, Function<String, T> parse) {
        try {
            return bindMeta(parse.apply(row.getConfigSnapshot()), row);
        } catch (RuntimeException e) {
            log.warn("策略版本配置快照为空或损坏, strategyVersionId={}", row.getId(), e);
            throw new KnowledgeException(ErrorCode.STRATEGY_VERSION_NOT_FOUND,
                    "策略版本配置已损坏，请检查该策略版本的配置内容");
        }
    }
}
