package com.workbuddy.biz.service.db.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.workbuddy.biz.mapper.WbSourceFileMapper;
import com.workbuddy.biz.service.db.WbSourceFileDbService;
import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 来源文件数据访问服务实现。
 */
@Service
public class WbSourceFileDbServiceImpl extends InfraDbServiceImpl<WbSourceFileMapper, WbSourceFile>
        implements WbSourceFileDbService {

    @Override
    public WbSourceFile findByFileId(String fileId) {
        LambdaQueryWrapper<WbSourceFile> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbSourceFile::getFileId, fileId).last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    @Override
    public List<WbSourceFile> listByFileIds(Collection<String> fileIds) {
        if (NullUtil.isNull(fileIds) || fileIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<WbSourceFile> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(WbSourceFile::getFileId, fileIds);
        return list(queryWrapper);
    }
}
