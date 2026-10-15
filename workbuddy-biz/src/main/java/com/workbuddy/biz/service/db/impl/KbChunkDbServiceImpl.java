package com.workbuddy.biz.service.db.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.mapper.KbChunkMapper;
import com.workbuddy.biz.service.db.KbChunkDbService;
import com.workbuddy.common.domain.entity.KbChunk;
import com.workbuddy.common.dto.request.stage.ChunkContentFilter;
import com.workbuddy.common.enums.chunk.ChunkContentType;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 切片数据访问服务实现。
 *
 * @author cxxl
 */
@Service
public class KbChunkDbServiceImpl extends InfraDbServiceImpl<KbChunkMapper, KbChunk>
        implements KbChunkDbService {

    @Override
    public List<KbChunk> listByChunkSetId(Long chunkSetId) {
        LambdaQueryWrapper<KbChunk> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbChunk::getChunkSetId, chunkSetId)
                .orderByAsc(KbChunk::getOrderNo)
                .orderByAsc(KbChunk::getId);
        return list(queryWrapper);
    }

    @Override
    public IPage<KbChunk> pageByChunkSetId(Long chunkSetId, ChunkContentFilter filter, long current, long size) {
        LambdaQueryWrapper<KbChunk> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbChunk::getChunkSetId, chunkSetId);
        applyFilter(queryWrapper, filter);
        queryWrapper.orderByAsc(KbChunk::getOrderNo).orderByAsc(KbChunk::getId);
        return page(new Page<>(current, size), queryWrapper);
    }

    /** 过滤条件下推：类型等同、兜底按内容类型判定、有无父片按 parent_chunk_id 判定 */
    private void applyFilter(LambdaQueryWrapper<KbChunk> queryWrapper, ChunkContentFilter filter) {
        if (NullUtil.isNull(filter)) {
            return;
        }
        if (StrUtil.isNotBlank(filter.contentType())) {
            queryWrapper.eq(KbChunk::getContentType, filter.contentType());
        }
        String fallbackType = ChunkContentType.FALLBACK.name();
        if (Boolean.TRUE.equals(filter.fallback())) {
            queryWrapper.eq(KbChunk::getContentType, fallbackType);
        }
        if (Boolean.FALSE.equals(filter.fallback())) {
            queryWrapper.ne(KbChunk::getContentType, fallbackType);
        }
        if (Boolean.TRUE.equals(filter.hasParent())) {
            queryWrapper.isNotNull(KbChunk::getParentChunkId).ne(KbChunk::getParentChunkId, "");
        }
        if (Boolean.FALSE.equals(filter.hasParent())) {
            // 无父片 = 父片（自身是章节锚点）与孤儿片；DB 侧把空串与 NULL 一起当成"没有父片"
            queryWrapper.and(wrapper -> wrapper.isNull(KbChunk::getParentChunkId)
                    .or().eq(KbChunk::getParentChunkId, ""));
        }
    }
}
