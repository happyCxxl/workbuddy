package com.workbuddy.biz.service.db;

import com.workbuddy.common.domain.entity.KbChunkSet;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.List;

/**
 * 切片产物集合数据访问服务。
 *
 * @author cxxl
 */
public interface KbChunkSetDbService extends InfraDbService<KbChunkSet> {

    /**
     * 按产物指纹精确取切片集合（历史任务详情数据源；无 → null），id 降序取最新。
     */
    KbChunkSet getByArtifactId(String artifactId);

    /**
     * 取文件结果最新的切片集合（无 → null；向量化血缘回填数据源）。
     */
    KbChunkSet getLatestByFileResultId(Long fileResultId);

    /** 批量查多文件全部切片集合（id 升序；组合枚举数据源） */
    List<KbChunkSet> listByFileResultIds(List<Long> fileResultIds);

    /**
     * 查单文件全部切片集合（id 升序；执行链血缘统计数据源）。
     */
    List<KbChunkSet> listByFileResultId(Long fileResultId);
}
