package com.workbuddy.filecenter.db;

import com.workbuddy.common.domain.entity.WbFileObject;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.Collection;
import java.util.List;

/**
 * 文件档案数据访问。
 */
public interface WbFileObjectDbService extends InfraDbService<WbFileObject> {

    /** 按文件 ID 查档案 */
    WbFileObject getByFileId(String fileId);

    /** 按文件 ID 批量查档案（空集合返回空列表） */
    List<WbFileObject> listByFileIds(Collection<String> fileIds);
}
