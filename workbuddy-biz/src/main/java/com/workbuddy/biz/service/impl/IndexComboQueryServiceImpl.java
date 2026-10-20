package com.workbuddy.biz.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.biz.service.IndexComboQueryService;
import com.workbuddy.biz.service.db.WbFileResultDbService;
import com.workbuddy.biz.service.support.IndexComboReconciler;
import com.workbuddy.common.domain.entity.WbFileResult;
import com.workbuddy.common.dto.response.index.IndexComboVO;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.indexing.ComboSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 索引组合查询实现：按范围内文件枚举预处理 × 切片 × 向量策略组合，成员与向量数走对账器单一取数口径。
 */
@Service
@RequiredArgsConstructor
public class IndexComboQueryServiceImpl implements IndexComboQueryService {

    private final WbFileResultDbService fileResultDbService;
    private final IndexComboReconciler indexComboReconciler;

    /**
     * 列出可构建的索引组合。
     *
     * @param knowledgeBaseId 知识库 ID
     * @param fileResultIds   限定参与的文件结果 ID（可空）
     * @return 组合列表
     */
    @Override
    public List<IndexComboVO> listCombos(Long knowledgeBaseId, List<Long> fileResultIds) {
        // 组合 × 成员口径（测评模式构建弹窗数据源）：候选三元组 = 范围内各文件各策略历史的并集；
        // 成员 = 该组合下有完整成功产物链的文件（selectComboProducts 单一取数口径，与构建对账同口径）。
        // 注意：与生产路径 enumerateCombos 的"全库交集"口径不同（本方法按成员子集返回，交集口径不动）。
        boolean scoped = NullUtil.isNotNull(fileResultIds) && !fileResultIds.isEmpty();
        List<Long> fileIds = scoped ? new ArrayList<>(fileResultIds)
                : fileResultDbService.listByKb(knowledgeBaseId).stream().map(WbFileResult::getId).toList();
        if (fileIds.isEmpty()) {
            return List.of();
        }
        IndexComboReconciler.ComboCatalog catalog = indexComboReconciler.catalog(fileIds);

        List<IndexComboVO> vos = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : catalog.preprocessByChunk().entrySet()) {
            for (String preprocess : entry.getValue()) {
                for (String embedStrategy : catalog.embedStrategies()) {
                    ComboSnapshot combo = ComboSnapshot.of(preprocess, entry.getKey(), embedStrategy);
                    List<Long> members = new ArrayList<>();
                    int vectorCount = 0;
                    for (Long fileId : fileIds) {
                        IndexComboReconciler.ComboProducts products =
                                indexComboReconciler.selectComboProducts(combo, fileId,
                                        catalog.latestChunk(), catalog.latestEmbed());
                        if (products.complete()) {
                            members.add(fileId);
                            vectorCount += ObjectUtil.defaultIfNull(products.embedRow().getRecordCount(), 0);
                        }
                    }
                    if (!members.isEmpty()) {
                        vos.add(toComboVO(combo, members, vectorCount));
                    }
                }
            }
        }
        return vos;
    }

    /** 组合 → 可构建组合视图（成员子集 + 文件数 × 向量数预览） */
    private IndexComboVO toComboVO(ComboSnapshot combo, List<Long> members, int vectorCount) {
        IndexComboVO vo = new IndexComboVO();
        vo.setFileScopeMode("ALL");
        vo.setChunkStrategy(combo.getChunkStrategy());
        vo.setEmbedStrategy(combo.getEmbedStrategy());
        vo.setShape(combo.getShape());
        vo.setStageStrategies(combo.getStageStrategies());
        vo.setComplete(true);
        vo.setFileResultIds(members);
        vo.setFileCount(members.size());
        vo.setVectorCount(vectorCount);
        return vo;
    }
}
