package com.workbuddy.biz.service.db.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.mapper.WbChunkMapper;
import com.workbuddy.biz.service.db.WbChunkDbService;
import com.workbuddy.common.domain.entity.WbChunk;
import com.workbuddy.common.dto.request.stage.ChunkContentFilterDTO;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 切片数据访问服务实现。
 */
@Service
public class WbChunkDbServiceImpl extends InfraDbServiceImpl<WbChunkMapper, WbChunk>
        implements WbChunkDbService {

    /**
     * 取某切片集合下的全部切片。
     *
     * @param chunkSetId 切片集合 ID
     * @return 切片列表
     */
    @Override
    public List<WbChunk> listByChunkSetId(Long chunkSetId) {
        LambdaQueryWrapper<WbChunk> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbChunk::getChunkSetId, chunkSetId)
                .orderByAsc(WbChunk::getOrderNo)
                .orderByAsc(WbChunk::getId);
        return list(queryWrapper);
    }

    /**
     * 按内容过滤条件分页查切片。
     *
     * @param chunkSetId 切片集合 ID
     * @param filter     内容过滤条件（可空）
     * @param current    页码
     * @param size       每页条数
     * @return 切片分页结果
     */
    @Override
    public IPage<WbChunk> pageByChunkSetId(Long chunkSetId, ChunkContentFilterDTO filter, long current, long size) {
        LambdaQueryWrapper<WbChunk> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbChunk::getChunkSetId, chunkSetId);
        applyFilter(queryWrapper, filter);
        queryWrapper.orderByAsc(WbChunk::getOrderNo).orderByAsc(WbChunk::getId);
        return page(new Page<>(current, size), queryWrapper);
    }

    /** 过滤条件下推：类型等同、兜底按内容类型判定、有无父片按 parent_chunk_id 判定 */
    private void applyFilter(LambdaQueryWrapper<WbChunk> queryWrapper, ChunkContentFilterDTO filter) {
        if (NullUtil.isNull(filter)) {
            return;
        }
        if (StrUtil.isNotBlank(filter.contentType())) {
            queryWrapper.eq(WbChunk::getContentType, filter.contentType());
        }
        String fallbackType = ChunkContentType.FALLBACK.name();
        if (Boolean.TRUE.equals(filter.fallback())) {
            queryWrapper.eq(WbChunk::getContentType, fallbackType);
        }
        if (Boolean.FALSE.equals(filter.fallback())) {
            queryWrapper.ne(WbChunk::getContentType, fallbackType);
        }
        if (Boolean.TRUE.equals(filter.hasParent())) {
            queryWrapper.isNotNull(WbChunk::getParentChunkId).ne(WbChunk::getParentChunkId, "");
        }
        if (Boolean.FALSE.equals(filter.hasParent())) {
            // 无父片 = 父片（自身是章节锚点）与孤儿片；DB 侧把空串与 NULL 一起当成"没有父片"
            queryWrapper.and(wrapper -> wrapper.isNull(WbChunk::getParentChunkId)
                    .or().eq(WbChunk::getParentChunkId, ""));
        }
    }
}
