package com.workbuddy.filecenter.db.impl;

import com.workbuddy.common.domain.entity.WbFileObject;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.db.WbFileObjectDbService;
import com.workbuddy.filecenter.mapper.WbFileObjectMapper;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 文件档案数据访问实现。
 *
 * @author cxxl
 */
@Service
public class WbFileObjectDbServiceImpl extends InfraDbServiceImpl<WbFileObjectMapper, WbFileObject>
        implements WbFileObjectDbService {

    @Override
    public WbFileObject getByFileId(String fileId) {
        return this.lambdaQuery().eq(WbFileObject::getFileId, fileId).one();
    }

    @Override
    public List<WbFileObject> listByFileIds(Collection<String> fileIds) {
        if (NullUtil.isNull(fileIds) || fileIds.isEmpty()) {
            return List.of();
        }
        return this.lambdaQuery().in(WbFileObject::getFileId, fileIds).list();
    }
}
