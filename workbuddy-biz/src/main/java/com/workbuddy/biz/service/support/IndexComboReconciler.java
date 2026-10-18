package com.workbuddy.biz.service.support;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.biz.service.db.WbChunkSetDbService;
import com.workbuddy.biz.service.db.WbEmbeddingSetDbService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.common.domain.embed.EmbeddingRecord;
import com.workbuddy.common.domain.embed.EmbeddingSet;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbEmbeddingSet;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.indexing.ComboSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 索引组合对账器：
 * 组合范围内逐文件取「该组合血缘匹配的最新成功」CHUNK+EMBED 产物，计算期望 chunkId 集合
 * （**以校验时刻实时重算**——构建期间新完成的文件天然被吸收）+ 维度/完整性/血缘结论 + 冒烟样本。
 * 构建任务全量对账（IndexBuildTaskRunner）与 validate API 共用同一口径，单一事实源。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexComboReconciler {

    private final WbFileResultDbService fileResultDbService;
    private final WbChunkSetDbService chunkSetDbService;
    private final WbEmbeddingSetDbService embeddingSetDbService;
    private final IndexLineageResolver lineageResolver;
    private final IndexRowAssembler indexRowAssembler;

    /**
     * 对账期望（实时）。
     *
     * @param sampleVector  冒烟样本：首条带向量的 SUCCESS/CACHED 记录（无 → null）
     * @param sampleKeyword 冒烟样本：首条非空 inputText 前 6 字（无 → null）
     */
    public record ComboExpectation(Set<String> chunkIds, int vectorCount, int dimension,
                                   boolean complete, String gap, boolean dimConsistent, String dimError,
                                   List<Float> sampleVector, String sampleKeyword) {
    }

    /**
     * 单文件组合产物（单一取数口径）：该文件在组合下的最新成功切片/向量行；
     * 缺口时 gap 非空（chunkRow/embedRow 为 null）。
     */
    public record ComboProducts(WbChunkSet chunkRow, WbEmbeddingSet embedRow, String gap) {

        public boolean complete() {
            return NullUtil.isNull(gap);
        }
    }

    /**
     * 组合枚举取数目录（枚举/列表弹窗共用批次口径）：文件级最新切片/向量行 +
     * 候选三元组（预处理策略 × 切片策略）对 + 向量策略集（血统缺失的切片策略不参与）。
     */
    public record ComboCatalog(Map<String, WbChunkSet> latestChunk, Map<String, WbEmbeddingSet> latestEmbed,
                               Map<String, Set<String>> preprocessByChunk, Set<String> embedStrategies) {
    }

    /**
     * 组合枚举取数目录（批量预取 + 候选三元组生成，单一口径）：
     * 预处理策略由切片行的上游产物血缘解析（血统缺失 → 该切片策略不进入候选）。
     */
    public ComboCatalog catalog(List<Long> fileResultIds) {
        Map<String, WbChunkSet> latestChunk = latestChunkMap(fileResultIds);
        Map<String, WbEmbeddingSet> latestEmbed = latestEmbedMap(fileResultIds);
        Map<String, Set<String>> preprocessByChunk = new LinkedHashMap<>();
        for (WbChunkSet row : latestChunk.values()) {
            String preprocess = lineageResolver.resolvePreprocessStrategy(row.getUpstreamProductId());
            if (StrUtil.isBlank(preprocess)) {
                continue;
            }
            preprocessByChunk.computeIfAbsent(row.getChunkStrategyVersion(), k -> new LinkedHashSet<>())
                    .add(preprocess);
        }
        Set<String> embedStrategies = latestEmbed.values().stream()
                .map(WbEmbeddingSet::getStrategyVersion)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new ComboCatalog(latestChunk, latestEmbed, preprocessByChunk, embedStrategies);
    }

    /** 文件级「同策略最新成功」切片行（追加表取 id 最大；对账/回填/枚举共用批次口径）。 */
    public Map<String, WbChunkSet> latestChunkMap(List<Long> fileResultIds) {
        Map<String, WbChunkSet> latest = new LinkedHashMap<>();
        for (WbChunkSet set : chunkSetDbService.listByFileResultIds(fileResultIds)) {
            String key = set.getFileResultId() + "#" + set.getChunkStrategyVersion();
            latest.merge(key, set, (a, b) -> a.getId() >= b.getId() ? a : b);
        }
        return latest;
    }

    /** 文件级「同策略最新成功」向量行（追加表取 id 最大；对账/回填/枚举共用批次口径）。 */
    public Map<String, WbEmbeddingSet> latestEmbedMap(List<Long> fileResultIds) {
        Map<String, WbEmbeddingSet> latest = new LinkedHashMap<>();
        for (WbEmbeddingSet set : embeddingSetDbService.listByFileResultIds(fileResultIds)) {
            String key = set.getFileResultId() + "#" + set.getStrategyVersion();
            latest.merge(key, set, (a, b) -> a.getId() >= b.getId() ? a : b);
        }
        return latest;
    }

    /**
     * 单一取数口径（枚举/对账/回填共用）：从预取的批次最新行中选出某文件在该组合下的产物。
     * 切片 = 该文件 chunkStrategy 最新成功；向量 = 该文件 embedStrategy 最新成功；
     * 切片血缘（上游预处理策略）必须匹配组合预处理策略——不匹配/缺失 → gap（血统纯净，不拿异血统顶替）。
     */
    public ComboProducts selectComboProducts(ComboSnapshot combo, Long fileResultId,
                                             Map<String, WbChunkSet> latestChunk,
                                             Map<String, WbEmbeddingSet> latestEmbed) {
        WbChunkSet chunkRow = latestChunk.get(fileResultId + "#" + combo.getChunkStrategy());
        WbEmbeddingSet embedRow = latestEmbed.get(fileResultId + "#" + combo.getEmbedStrategy());
        if (NullUtil.isNull(chunkRow) || NullUtil.isNull(embedRow)) {
            return new ComboProducts(null, null, "文件 " + fileResultId + " 缺少组合产物（切片策略 "
                    + combo.getChunkStrategy() + " / 向量策略 " + combo.getEmbedStrategy() + "）");
        }
        if (!combo.getPreprocessStrategy().equals(
                lineageResolver.resolvePreprocessStrategy(chunkRow.getUpstreamProductId()))) {
            return new ComboProducts(null, null, "文件 " + fileResultId + " 切片血统与组合预处理策略不符");
        }
        return new ComboProducts(chunkRow, embedRow, null);
    }

    /** 计算组合对账期望（实时重算口径）。 */
    public ComboExpectation computeExpected(Long kbId, ComboSnapshot combo) {
        // 对账不变量：组合必须携带三环节策略维度（缺失即坏数据，已在快照读取处显式拒绝）
        if (NullUtil.isNull(combo) || !combo.hasCompleteStageStrategies()) {
            return new ComboExpectation(Set.of(), 0, 0, false,
                    "组合缺失环节策略维度（需废弃重灌）", true, null, null, null);
        }
        Map<Long, WbFileResult> filesById = new LinkedHashMap<>();
        for (WbFileResult file : fileResultDbService.listByKb(kbId)) {
            filesById.put(file.getId(), file);
        }
        List<Long> scope;
        if (combo.isListScope() && NullUtil.isNotNull(combo.getFileResultIds())) {
            scope = combo.getFileResultIds();
        } else {
            scope = new ArrayList<>(filesById.keySet());
        }
        if (scope.isEmpty()) {
            return new ComboExpectation(Set.of(), 0, 0, false, "组合范围内无文件", true, null, null, null);
        }
        for (Long fileId : scope) {
            if (!filesById.containsKey(fileId)) {
                return new ComboExpectation(Set.of(), 0, 0, false,
                        "组合范围内存在非本库文件: " + fileId, true, null, null, null);
            }
        }

        Map<String, WbChunkSet> latestChunk = latestChunkMap(scope);
        Map<String, WbEmbeddingSet> latestEmbed = latestEmbedMap(scope);

        Set<Integer> dims = new HashSet<>();
        Set<String> chunkIds = new HashSet<>();
        int vectorCount = 0;
        List<Float> sampleVector = null;
        String sampleKeyword = null;
        for (Long fileId : scope) {
            ComboProducts products = selectComboProducts(combo, fileId, latestChunk, latestEmbed);
            if (!products.complete()) {
                return new ComboExpectation(Set.of(), 0, 0, false, products.gap(), true, null, null, null);
            }
            dims.add(ObjectUtil.defaultIfNull(products.embedRow().getDimension(), 0));
            EmbeddingSet embedSet = indexRowAssembler.readEmbeddingSet(ObjectRef.ofEmbeddingSet(products.embedRow()));
            if (NullUtil.isNull(embedSet) || NullUtil.isNull(embedSet.getRecords())) {
                return new ComboExpectation(Set.of(), 0, 0, false,
                        "文件 " + fileId + " 向量产物读取失败", true, null, null, null);
            }
            for (EmbeddingRecord record : embedSet.getRecords()) {
                if (!"SUCCESS".equals(record.getStatus()) && !"CACHED".equals(record.getStatus())) {
                    continue;
                }
                chunkIds.add(record.getChunkId());
                if (NullUtil.isNotNull(record.getVector()) && !record.getVector().isEmpty()) {
                    vectorCount++;
                    if (NullUtil.isNull(sampleVector)) {
                        sampleVector = record.getVector();
                    }
                }
                if (NullUtil.isNull(sampleKeyword) && StrUtil.isNotBlank(record.getInputText())) {
                    sampleKeyword = StrUtil.maxLength(record.getInputText(), 6);
                }
            }
        }
        if (dims.size() > 1) {
            return new ComboExpectation(Set.of(), 0, 0, true, null, false,
                    "向量维度不一致: " + dims, null, null);
        }
        int dimension = dims.isEmpty() ? 0 : dims.iterator().next();
        return new ComboExpectation(chunkIds, vectorCount, dimension, true, null, true, null,
                sampleVector, sampleKeyword);
    }
}
