package com.workbuddy.common.convert.knowledge;

import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.common.config.BaseInfoConvertConfig;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.entity.WbChunk;
import com.workbuddy.common.domain.entity.WbChunkSet;
import com.workbuddy.common.domain.entity.WbPipelineProduct;
import com.workbuddy.common.dto.response.chunk.ChunkItemVO;
import com.workbuddy.common.enums.task.RowStatus;
import com.workbuddy.common.utils.NullUtil;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 切片转换：切片行/集合行 ↔ VO，以及切片产物 → 落库行。只搬字段，不查库、不算统计。
 */
@Mapper(config = BaseInfoConvertConfig.class)
public interface ChunkConverter {

    /**
     * 单条切片行 → 切片条目 VO（产物侧字段留空，由调用方补齐）。
     *
     * @param chunk 切片行
     * @return 切片条目 VO
     */
    @Mapping(target = "sourceElementCount", ignore = true)
    @Mapping(target = "fallbackReason", ignore = true)
    ChunkItemVO mapChunk(WbChunk chunk);

    /**
     * 切片行 → 切片条目 VO 列表：来源元素个数与兜底原因按 chunkId 从产物补齐。
     *
     * @param chunks        切片行（DB）
     * @param productChunks 切片产物里的片（键 = chunkId）
     * @return 切片条目 VO 列表
     */
    default List<ChunkItemVO> toChunkItemVOs(List<WbChunk> chunks, Map<String, Chunk> productChunks) {
        return ObjectUtil.defaultIfNull(chunks, new ArrayList<WbChunk>()).stream()
                .map(chunk -> {
                    ChunkItemVO vo = mapChunk(chunk);
                    Chunk productChunk = NullUtil.isNull(productChunks) || NullUtil.isNull(chunk.getChunkId()) ?
                            null : productChunks.get(chunk.getChunkId());
                    if (NullUtil.isNotNull(productChunk)) {
                        vo.setSourceElementCount(ObjectUtil.defaultIfNull(productChunk.getSourceElementIds(),
                                new ArrayList<String>()).size());
                        vo.setFallbackReason(productChunk.getFallbackReason());
                    }
                    return vo;
                })
                .toList();
    }

    /**
     * 切片集合行：切片数与总字符由调用方算好传入。
     *
     * @param fileResultId      文件结果 ID
     * @param upstreamProductId 上游产物 ID
     * @param strategyVersion   切片策略完整版本
     * @param chunkCount        切片数
     * @param totalChars        总字符数
     * @param product           本环节产物行（提供产物引用列）
     * @return 切片集合行（未保存）
     */
    default WbChunkSet toChunkSetRow(Long fileResultId, Long upstreamProductId, String strategyVersion,
                                     Integer chunkCount, Integer totalChars, WbPipelineProduct product) {
        WbChunkSet row = new WbChunkSet();
        row.setFileResultId(fileResultId);
        row.setUpstreamProductId(upstreamProductId);
        row.setChunkStrategyVersion(strategyVersion);
        row.setChunkCount(chunkCount);
        row.setTotalChars(totalChars);
        row.setStatus(RowStatus.ACTIVE.name());
        row.setArtifactId(product.getArtifactId());
        row.setStorageType(product.getStorageType());
        row.setStorageSourceId(product.getStorageSourceId());
        row.setBucket(product.getBucket());
        return row;
    }

    /**
     * 切片行：页码范围由调用方格式化后传入（落库口径在调用方）。
     *
     * @param chunk           切片产物元素
     * @param chunkSetId      切片集合行 ID
     * @param strategyVersion 切片策略完整版本
     * @param pageRange       已格式化的页码范围
     * @return 切片行（未保存）
     */
    default WbChunk toChunkRow(Chunk chunk, Long chunkSetId, String strategyVersion, String pageRange) {
        WbChunk row = new WbChunk();
        row.setChunkSetId(chunkSetId);
        row.setChunkId(chunk.getChunkId());
        row.setParentChunkId(chunk.getParentChunkId());
        row.setContent(chunk.getContent());
        row.setContentType(chunk.getContentType());
        row.setTitlePath(chunk.getTitlePath());
        row.setPageRange(pageRange);
        row.setTableRef(chunk.getTableRef());
        row.setOrderNo(chunk.getOrder());
        row.setCharCount(chunk.getCharCount());
        row.setTokenCount(chunk.getTokenCount());
        row.setStrategyVersion(strategyVersion);
        return row;
    }
}
