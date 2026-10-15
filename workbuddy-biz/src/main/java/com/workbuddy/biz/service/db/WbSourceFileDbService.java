package com.workbuddy.biz.service.db;

import com.workbuddy.common.domain.entity.WbSourceFile;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.Collection;
import java.util.List;

/**
 * 来源文件数据访问服务（wb_source_file）。
 *
 * @author cxxl
 */
public interface WbSourceFileDbService extends InfraDbService<WbSourceFile> {

    /**
     * 按文件 ID 查来源文件。
     *
     * @param fileId 文件 ID（uk_file_id 唯一）
     * @return 来源文件实体；不存在返回 null
     */
    WbSourceFile findByFileId(String fileId);

    /**
     * 按文件 ID 批量查来源文件（列表展示用，避免逐行查库）。
     *
     * @param fileIds 文件 ID 集合
     * @return 命中的来源文件；集合为空时返回空列表
     */
    List<WbSourceFile> listByFileIds(Collection<String> fileIds);
}
