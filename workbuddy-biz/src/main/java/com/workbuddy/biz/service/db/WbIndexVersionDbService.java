package com.workbuddy.biz.service.db;

import com.baomidou.mybatisplus.extension.service.IService;
import com.workbuddy.common.domain.entity.WbIndexVersion;

import java.util.List;

/**
 * 索引版本数据访问服务。
 *
 * @author cxxl
 */
public interface WbIndexVersionDbService extends IService<WbIndexVersion> {

    /**
     * 集合下全部版本（新→旧）。
     */
    List<WbIndexVersion> listByIndexSetId(Long indexSetId);

    /**
     * 计算下一版本号（v1、v2…；并发下由 uk_set_version 兜底）。
     */
    String nextVersionNo(Long indexSetId);

    /**
     * 按构建任务 ID 取版本行（IndexBuildTaskRunner 由任务反查版本；无 → null）。
     */
    WbIndexVersion getByTaskId(Long taskId);
}
