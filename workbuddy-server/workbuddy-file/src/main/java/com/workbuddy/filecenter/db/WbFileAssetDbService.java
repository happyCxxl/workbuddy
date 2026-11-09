package com.workbuddy.filecenter.db;

import com.workbuddy.common.domain.entity.WbFileAsset;
import com.workbuddy.infra.persistence.InfraDbService;

import java.util.List;

/**
 * 解析资产档案数据访问：按归属查询与内容幂等判定。
 */
public interface WbFileAssetDbService extends InfraDbService<WbFileAsset> {

    /** 按归属 + 内容指纹取档案（同一次解析内同内容只登记一份）。 */
    WbFileAsset getByKnowledgeFileIdAndSha(Long knowledgeFileId, String sha256);

    /** 按归属取全部资产档案。 */
    List<WbFileAsset> listByKnowledgeFileId(Long knowledgeFileId);
}
