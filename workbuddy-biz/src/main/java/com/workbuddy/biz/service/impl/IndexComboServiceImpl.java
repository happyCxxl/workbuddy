package com.workbuddy.biz.service.impl;

import com.workbuddy.common.enums.knowledge.StrategyBindingSwitch;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.IndexComboService;
import com.workbuddy.biz.service.db.KbFileResultDbService;
import com.workbuddy.biz.service.db.KbPipelineStrategyVersionDbService;
import com.workbuddy.biz.service.db.KbStrategyBindingDbService;
import com.workbuddy.biz.service.db.KnowledgeBaseDbService;
import com.workbuddy.biz.service.support.IndexComboReconciler;
import com.workbuddy.common.domain.entity.KbFileResult;
import com.workbuddy.common.domain.entity.KbPipelineStrategyVersion;
import com.workbuddy.common.domain.entity.KbStrategyBinding;
import com.workbuddy.common.domain.entity.KnowledgeBase;
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
 *
 * @author cxxl
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexComboServiceImpl implements IndexComboService {

    private final KnowledgeBaseDbService knowledgeBaseDbService;
    private final KbStrategyBindingDbService strategyBindingDbService;
    private final KbPipelineStrategyVersionDbService strategyVersionDbService;
    private final KbFileResultDbService fileResultDbService;
    private final IndexComboReconciler indexComboReconciler;

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
        KbStrategyBinding binding = strategyBindingDbService.getByKbAndType(knowledgeBaseId, type);
        if (NullUtil.isNull(binding)) {
            return null;
        }
        KbPipelineStrategyVersion version = strategyVersionDbService.getById(binding.getStrategyVersionId());
        if (NullUtil.isNull(version)) {
            return null;
        }
        return version.getName() + "-" + version.getVersion();
    }

    /**
     * 绑定关闭场景：枚举「全部文件 × 已运行（chunk, embed）策略组合」中产物完整的组合。
     */
    public List<ComboSnapshot> enumerateCombos(Long knowledgeBaseId) {
        List<KbFileResult> files = fileResultDbService.listByKb(knowledgeBaseId);
        if (files.isEmpty()) {
            return List.of();
        }
        return enumerateCombos(files.stream().map(KbFileResult::getId).toList());
    }

    /**
     * 绑定关闭场景（评测冻结集）：枚举范围收窄到指定文件——
     * 只返回「范围内每个文件都完整」的三元组（fileResultIds 空 → 空列表）。
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
     */
    public List<ComboSnapshot> combosFor(Long knowledgeBaseId) {
        KnowledgeBase kb = knowledgeBaseDbService.getById(knowledgeBaseId);
        boolean bindingEnabled = NullUtil.isNull(kb) || StrategyBindingSwitch.isOn(kb.getStrategyBindingEnabled());
        if (bindingEnabled) {
            ComboSnapshot bound = resolveBoundCombo(knowledgeBaseId);
            return NullUtil.isNull(bound) ? List.of() : List.of(bound);
        }
        return enumerateCombos(knowledgeBaseId);
    }
}
