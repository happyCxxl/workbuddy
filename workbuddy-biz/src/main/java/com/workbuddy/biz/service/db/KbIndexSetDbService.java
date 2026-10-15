package com.workbuddy.biz.service.db;

import com.baomidou.mybatisplus.extension.service.IService;
import com.workbuddy.common.domain.entity.KbIndexSet;

import java.util.List;
import java.util.Map;

/**
 * 索引集合数据访问服务：一知识库一行，承载二级发布指针。
 *
 * @author cxxl
 */
public interface KbIndexSetDbService extends IService<KbIndexSet> {

    /**
     * 按知识库取集合行（无 → null）。
     */
    KbIndexSet getByKb(Long knowledgeBaseId);

    /**
     * 批量按知识库取集合行，供列表回填已发布索引版本（避免逐行查询）。
     *
     * @param knowledgeBaseIds 知识库 ID 列表
     * @return 知识库 ID → 集合行；无集合的知识库不出现在结果中
     */
    Map<Long, KbIndexSet> listByKbIds(List<Long> knowledgeBaseIds);

    /**
     * 按知识库取集合行，不存在则创建（uk_kb 兜底并发：冲突后重查返回既有行）。
     */
    KbIndexSet getOrCreateByKb(Long knowledgeBaseId);

    /**
     * 更新二级发布指针（发布/回退共用）。
     */
    void updatePublishedVersion(Long setId, Long versionId);
}
