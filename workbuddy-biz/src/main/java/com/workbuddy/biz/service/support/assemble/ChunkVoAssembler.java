package com.workbuddy.biz.service.support.assemble;

import cn.hutool.core.util.ObjectUtil;
import com.workbuddy.common.domain.chunk.Chunk;
import com.workbuddy.common.domain.entity.WbChunk;
import com.workbuddy.common.dto.response.chunk.ChunkItemVO;
import com.workbuddy.common.utils.NullUtil;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 切片详情 VO 映射：切片行 → 切片条目视图（编译期生成实现，运行期无反射）。
 *
 * <p>只做字段搬运；来源元素个数与兜底原因只在切片产物里，按 chunkId 关联补齐。
 */
@Mapper(componentModel = "spring")
public interface ChunkVoAssembler {

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
                    Chunk productChunk = productChunkOf(productChunks, chunk.getChunkId());
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
     * 产物侧的片（缺失返回 null）。
     *
     * @param productChunks 切片产物索引
     * @param chunkId       切片 ID
     * @return 产物里的切片；无产物或无对应片时为 null
     */
    default Chunk productChunkOf(Map<String, Chunk> productChunks, String chunkId) {
        return NullUtil.isNull(productChunks) || NullUtil.isNull(chunkId) ? null : productChunks.get(chunkId);
    }
}
