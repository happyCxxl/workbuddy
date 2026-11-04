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

    /**
     * 按 fileId 取数据源文件档案。
     *
     * @param fileId 文件 ID
     * @return 档案行；没有则为 null
     */
    @Override
    public WbSourceFile findByFileId(String fileId) {
        LambdaQueryWrapper<WbSourceFile> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(WbSourceFile::getFileId, fileId).last("LIMIT 1");
        return getOne(queryWrapper, false);
    }

    /**
     * 按 fileId 批量取档案。
     *
     * @param fileIds 文件 ID 集合
     * @return 命中的档案列表；入参为空时返回空列表
     */
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
