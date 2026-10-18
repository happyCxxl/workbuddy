package com.workbuddy.biz.service.support;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.entity.WbChunk;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.dto.response.chunk.ChunkItemVO;
import com.workbuddy.common.dto.response.chunk.ChunkSummaryVO;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.utils.NullUtil;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 切片详情 VO 组装器（纯映射，不查库）：切片集合/切片行 → 统计/切片列表视图。
 * 集合与切片由 Service 查好传入；任务/子步骤字段组装不归本类。
 */
@Component
public class ChunkVoAssembler {

    /**
     * 切片统计：总数/总字符/平均片长/父子数/类型分布（父子口径与 ChunkContentType 一致）。
     *
     * @param chunkSet 切片集合行
     * @param chunks   切片行列表
     * @return 切片统计视图
     */
    public ChunkSummaryVO toSummary(WbChunkSet chunkSet, List<WbChunk> chunks) {
        ChunkSummaryVO vo = new ChunkSummaryVO();
        vo.setChunkCount(chunkSet.getChunkCount());
        vo.setTotalChars(chunkSet.getTotalChars());
        // 父片 = SECTION；子片 = parentChunkId 非空；无章节归属的孤儿子片两者皆非（不计入父子数）
        int parentCount = 0;
        int childCount = 0;
        Map<String, Integer> typeCounts = new HashMap<>();
        for (WbChunk chunk : chunks) {
            if (ChunkContentType.SECTION.name().equals(chunk.getContentType())) {
                parentCount++;
            } else if (StrUtil.isNotBlank(chunk.getParentChunkId())) {
                childCount++;
            }
            typeCounts.merge(chunk.getContentType(), 1, Integer::sum);
        }
        vo.setParentChunkCount(parentCount);
        vo.setChildChunkCount(childCount);
        int chunkCount = ObjectUtil.defaultIfNull(chunkSet.getChunkCount(), 0);
        vo.setAvgChars(chunkCount > 0 && NullUtil.isNotNull(chunkSet.getTotalChars())
                ? (int) Math.round((double) chunkSet.getTotalChars() / chunkCount)
                : 0);
        vo.setTypeCounts(typeCounts);
        return vo;
    }

    /**
     * 切片行 → 切片条目 VO 列表。
     *
     * @param chunks          切片行（DB）
     * @param productChunks   切片产物里的片（键 = chunkId）：来源元素个数与兜底原因只在产物里，DB 无这两列
     * @return 切片条目 VO 列表
     */
    public List<ChunkItemVO> toChunkItemVOs(List<WbChunk> chunks, Map<String, Chunk> productChunks) {
        return ObjectUtil.defaultIfNull(chunks, new ArrayList<WbChunk>()).stream()
                .map(chunk -> toChunkItemVO(chunk, productChunkOf(productChunks, chunk.getChunkId())))
                .toList();
    }

    /** 产物侧的片（缺失返回 null） */
    private Chunk productChunkOf(Map<String, Chunk> productChunks, String chunkId) {
        return NullUtil.isNull(productChunks) || NullUtil.isNull(chunkId) ? null : productChunks.get(chunkId);
    }

    private ChunkItemVO toChunkItemVO(WbChunk chunk, Chunk productChunk) {
        ChunkItemVO vo = new ChunkItemVO();
        vo.setChunkId(chunk.getChunkId());
        vo.setParentChunkId(chunk.getParentChunkId());
        vo.setContent(chunk.getContent());
        vo.setContentType(chunk.getContentType());
        vo.setTitlePath(chunk.getTitlePath());
        vo.setPageRange(chunk.getPageRange());
        vo.setTableRef(chunk.getTableRef());
        vo.setOrderNo(chunk.getOrderNo());
        vo.setCharCount(chunk.getCharCount());
        vo.setTokenCount(chunk.getTokenCount());
        if (NullUtil.isNotNull(productChunk)) {
            vo.setSourceElementCount(ObjectUtil.defaultIfNull(productChunk.getSourceElementIds(),
                    new ArrayList<String>()).size());
            vo.setFallbackReason(productChunk.getFallbackReason());
        }
        return vo;
    }
}
