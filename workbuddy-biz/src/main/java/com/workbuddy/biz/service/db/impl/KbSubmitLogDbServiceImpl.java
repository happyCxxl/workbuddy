package com.workbuddy.biz.service.db.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.workbuddy.biz.mapper.KbSubmitLogMapper;
import com.workbuddy.biz.service.db.KbSubmitLogDbService;
import com.workbuddy.common.domain.entity.KbSubmitLog;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 提交日志数据访问服务实现。
 *
 * @author cxxl
 */
@Service
public class KbSubmitLogDbServiceImpl extends InfraDbServiceImpl<KbSubmitLogMapper, KbSubmitLog>
        implements KbSubmitLogDbService {

    @Override
    public KbSubmitLog getByRequestId(String requestId) {
        LambdaQueryWrapper<KbSubmitLog> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbSubmitLog::getRequestId, requestId).last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public IPage<KbSubmitLog> pageByKb(long current, long size, Long knowledgeBaseId, String status, String fileName) {
        LambdaQueryWrapper<KbSubmitLog> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(KbSubmitLog::getKnowledgeBaseId, knowledgeBaseId)
                .eq(StrUtil.isNotBlank(status), KbSubmitLog::getStatus, status)
                .like(StrUtil.isNotBlank(fileName), KbSubmitLog::getFileName, fileName)
                .orderByDesc(KbSubmitLog::getId);
        return page(new Page<>(current, size), queryWrapper);
    }

    @Override
    public IPage<KbSubmitLog> pageRecentByUser(long current, long size, Long userId) {
        LambdaQueryWrapper<KbSubmitLog> queryWrapper = new LambdaQueryWrapper<>();
        // 跨库：不加 knowledge_base_id 条件（传 null 会变成 = NULL 恒不成立）；只按提交人隔离
        queryWrapper.eq(KbSubmitLog::getUserId, userId)
                .orderByDesc(KbSubmitLog::getId);
        return page(new Page<>(current, size), queryWrapper);
    }
}
