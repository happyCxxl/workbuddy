package com.workbuddy.biz.service.impl;

import com.workbuddy.common.enums.knowledge.StrategyBindingSwitch;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.IndexComboService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.db.WbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.WbStrategyBindingDbService;
import com.workbuddy.biz.service.db.WbKnowledgeBaseDbService;
import com.workbuddy.biz.service.support.IndexComboReconciler;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.entity.WbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.WbStrategyBinding;
import com.workbuddy.common.domain.entity.WbKnowledgeBase;
import com.workbuddy.common.enums.task.PipelineStage;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.indexing.ComboSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 索引组合口径服务实现（组合 = 预处理×切片×向量化 环节策略映射）。
 * 血缘与枚举取数走 IndexComboReconciler（预处理策略沿切片产物的上游链读取，capabilitySnapshot 为载体，不新增列）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexComboServiceImpl implements IndexComboService {

    private final WbKnowledgeBaseDbService knowledgeBaseDbService;
    private final WbStrategyBindingDbService strategyBindingDbService;
    private final WbPipelineStrategyVersionDbService strategyVersionDbService;
    private final WbFileResultDbService fileResultDbService;
    private final IndexComboReconciler indexComboReconciler;

    /**
     * 按知识库绑定解析组合。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 绑定形成的组合；任一环节绑定缺失时为 null
     */
    @Override
    public ComboSnapshot resolveBoundCombo(Long knowledgeBaseId) {
        String preprocess = boundStrategyOf(knowledgeBaseId, PipelineStage.PREPROCESS.name());
        String chunk = boundStrategyOf(knowledgeBaseId, PipelineStage.CHUNK.name());
        String embed = boundStrategyOf(knowledgeBaseId, PipelineStage.EMBED.name());
        if (StrUtil.hasBlank(preprocess, chunk, embed)) {
            log.info("===> IndexComboServiceImpl 绑定策略不齐全, 无法形成组合, kbId={}, preprocess={}, chunk={}, embed={}",
                    knowledgeBaseId, preprocess, chunk, embed);
            return null;
        }
        return ComboSnapshot.of(preprocess, chunk, embed);
    }

    /** 单环节绑定策略解析：绑定缺失/版本行缺失 → null */
    private String boundStrategyOf(Long knowledgeBaseId, String type) {
        WbStrategyBinding binding = strategyBindingDbService.getByKbAndType(knowledgeBaseId, type);
        if (NullUtil.isNull(binding)) {
            return null;
        }
        WbPipelineStrategyVersion version = strategyVersionDbService.getById(binding.getStrategyVersionId());
        if (NullUtil.isNull(version)) {
            return null;
        }
        return version.getName() + "-" + version.getVersion();
    }

    /**
     * 绑定关闭场景：枚举「全部文件 × 已运行（chunk, embed）策略组合」中产物完整的组合。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 产物完整的组合列表；知识库下没有文件时为空列表
     */
    public List<ComboSnapshot> enumerateCombos(Long knowledgeBaseId) {
        List<WbFileResult> files = fileResultDbService.listByKb(knowledgeBaseId);
        if (files.isEmpty()) {
            return List.of();
        }
        return enumerateCombos(files.stream().map(WbFileResult::getId).toList());
    }

    /**
     * 绑定关闭场景（评测冻结集）：枚举范围收窄到指定文件——
     * 只返回「范围内每个文件都完整」的三元组。
     *
     * @param fileResultIds 范围内的文件结果 ID
     * @return 完整的组合列表；入参为空时返回空列表
     */
    public List<ComboSnapshot> enumerateCombos(List<Long> fileResultIds) {
        if (NullUtil.isNull(fileResultIds) || fileResultIds.isEmpty()) {
            return List.of();
        }
        // 候选三元组 = 去重后的（预处理策略 × 切片策略）对 × 向量策略（目录批量取数，单一口径）；
        // 完整性判定复用 selectComboProducts（枚举/对账/回填同口径）
        IndexComboReconciler.ComboCatalog catalog = indexComboReconciler.catalog(fileResultIds);
        List<ComboSnapshot> combos = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : catalog.preprocessByChunk().entrySet()) {
            String chunkStrategy = entry.getKey();
            for (String preprocess : entry.getValue()) {
                for (String embedStrategy : catalog.embedStrategies()) {
                    ComboSnapshot candidate = ComboSnapshot.of(preprocess, chunkStrategy, embedStrategy);
                    boolean complete = fileResultIds.stream().allMatch(f ->
                            indexComboReconciler.selectComboProducts(candidate, f,
                                    catalog.latestChunk(), catalog.latestEmbed()).complete());
                    if (complete) {
                        combos.add(candidate);
                    }
                }
            }
        }
        return combos;
    }

    /**
     * 按知识库策略绑定开关分发：开=单组合（空列表表示绑定不齐全）；关=枚举完整组合。
     *
     * @param knowledgeBaseId 知识库 ID
     * @return 可用组合列表
     */
    public List<ComboSnapshot> combosFor(Long knowledgeBaseId) {
        WbKnowledgeBase kb = knowledgeBaseDbService.getById(knowledgeBaseId);
        boolean bindingEnabled = NullUtil.isNull(kb) || StrategyBindingSwitch.isOn(kb.getStrategyBindingEnabled());
        if (bindingEnabled) {
            ComboSnapshot bound = resolveBoundCombo(knowledgeBaseId);
            return NullUtil.isNull(bound) ? List.of() : List.of(bound);
        }
        return enumerateCombos(knowledgeBaseId);
    }
}
