package com.workbuddy.biz.service.db.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.mapper.WbSubmitLogMapper;
import com.workbuddy.biz.service.db.WbSubmitLogDbService;
import com.workbuddy.common.domain.entity.WbSubmitLog;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 提交日志数据访问服务实现。
 */
@Service
public class WbSubmitLogDbServiceImpl extends InfraDbServiceImpl<WbSubmitLogMapper, WbSubmitLog>
        implements WbSubmitLogDbService {

    @Override
    public WbSubmitLog getByRequestId(String requestId) {
        LambdaQueryWrapper<WbSubmitLog> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbSubmitLog::getRequestId, requestId).last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public IPage<WbSubmitLog> pageByKb(long current, long size, Long knowledgeBaseId, String status, String fileName) {
        LambdaQueryWrapper<WbSubmitLog> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbSubmitLog::getKnowledgeBaseId, knowledgeBaseId)
                .eq(StrUtil.isNotBlank(status), WbSubmitLog::getStatus, status)
                .like(StrUtil.isNotBlank(fileName), WbSubmitLog::getFileName, fileName)
                .orderByDesc(WbSubmitLog::getId);
        return page(new Page<>(current, size), queryWrapper);
    }

    @Override
    public IPage<WbSubmitLog> pageRecentByUser(long current, long size, Long userId) {
        LambdaQueryWrapper<WbSubmitLog> queryWrapper = new LambdaQueryWrapper<>();
        // 跨库：不加 knowledge_base_id 条件（传 null 会变成 = NULL 恒不成立）；只按提交人隔离
        queryWrapper.eq(WbSubmitLog::getUserId, userId)
                .orderByDesc(WbSubmitLog::getId);
        return page(new Page<>(current, size), queryWrapper);
    }
}
