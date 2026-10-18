package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.workbuddy.biz.mapper.WbIndexVersionMapper;
import com.workbuddy.biz.service.db.WbIndexVersionDbService;
import com.workbuddy.common.domain.entity.WbIndexVersion;
import com.workbuddy.common.utils.NullUtil;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 索引版本数据访问服务实现。
 */
@Service
public class WbIndexVersionDbServiceImpl extends ServiceImpl<WbIndexVersionMapper, WbIndexVersion>
        implements WbIndexVersionDbService {

    /**
     * 取索引集合下的全部版本。
     *
     * @param indexSetId 索引集合 ID
     * @return 版本列表
     */
    @Override
    public List<WbIndexVersion> listByIndexSetId(Long indexSetId) {
        LambdaQueryWrapper<WbIndexVersion> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbIndexVersion::getIndexSetId, indexSetId)
                .orderByDesc(WbIndexVersion::getId);
        return list(queryWrapper);
    }

    /**
     * 生成下一个版本号（v1、v2 …）。
     *
     * @param indexSetId 索引集合 ID
     * @return 新的版本号
     */
    @Override
    public String nextVersionNo(Long indexSetId) {
        // 取现有最大数字后缀 + 1：回收会物理删行，行数+1 在删行后会重号；
        // 并发冲突仍由 uk_set_version 兜底（重取时 MAX 已读到赢家新值）
        QueryWrapper<WbIndexVersion> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("IFNULL(MAX(CAST(SUBSTRING(version_no, 2) AS UNSIGNED)), 0)")
                .eq("index_set_id", indexSetId);
        List<Object> result = baseMapper.selectObjs(queryWrapper);
        long max = NullUtil.isNull(result) || result.isEmpty() || NullUtil.isNull(result.getFirst())
                ? 0L : ((Number) result.getFirst()).longValue();
        return "v" + (max + 1);
    }

    /**
     * 按构建任务取版本。
     *
     * @param taskId 任务 ID
     * @return 版本行；没有则为 null
     */
    @Override
    public WbIndexVersion getByTaskId(Long taskId) {
        LambdaQueryWrapper<WbIndexVersion> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbIndexVersion::getTaskId, taskId)
                .last("LIMIT 1");
        return getOne(queryWrapper, false);
    }
}
