package com.workbuddy.filecenter.db;

import com.workbuddy.common.domain.entity.KbFileObject;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.Collection;
import java.util.List;

/**
 * 文件档案数据访问。
 *
 * @author cxxl
 */
public interface KbFileObjectDbService extends InfraDbService<KbFileObject> {

    /** 按文件 ID 查档案 */
    KbFileObject getByFileId(String fileId);

    /** 按文件 ID 批量查档案（空集合返回空列表） */
    List<KbFileObject> listByFileIds(Collection<String> fileIds);
}
