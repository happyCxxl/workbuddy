package com.workbuddy.biz.service.db;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.workbuddy.common.domain.entity.KbChunk;
import com.workbuddy.common.dto.request.stage.ChunkContentFilter;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.List;

/**
 * 切片数据访问服务（批量插入由 InfraDbService.saveBatch(list, size) 承担，≤500/批）。
 *
 * @author cxxl
 */
public interface KbChunkDbService extends InfraDbService<KbChunk> {

    /**
     * 按切片集合取全部切片（集合内顺序号升序；切片详情数据源）。
     */
    List<KbChunk> listByChunkSetId(Long chunkSetId);

    /**
     * 按切片集合分页取切片（集合内顺序号升序）：过滤（类型 / 是否兜底 / 有无父片）与分页都下推到 DB。
     *
     * <p>返回的分页对象同时带过滤后的总条数，total 与 truncated 都按该口径给。
     *
     * @param chunkSetId 切片集合 ID
     * @param filter     过滤条件（可空，按不过滤）
     * @param current    页码（从 1 起）
     * @param size       每页条数
     * @return 分页结果（含过滤后的总条数）
     */
    IPage<KbChunk> pageByChunkSetId(Long chunkSetId, ChunkContentFilter filter, long current, long size);
}
