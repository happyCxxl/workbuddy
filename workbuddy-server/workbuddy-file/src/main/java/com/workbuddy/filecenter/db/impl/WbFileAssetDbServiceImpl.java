package com.workbuddy.filecenter.db.impl;

import com.workbuddy.common.domain.entity.WbFileAsset;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.db.WbFileAssetDbService;
import com.workbuddy.filecenter.mapper.WbFileAssetMapper;
import com.workbuddy.infra.persistence.InfraDbServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 解析资产档案数据访问实现。
 */
@Service
public class WbFileAssetDbServiceImpl extends InfraDbServiceImpl<WbFileAssetMapper, WbFileAsset>
        implements WbFileAssetDbService {

    /**
     * 按归属 + 内容指纹取档案。
     *
     * @param knowledgeFileId 归属知识库文件
     * @param sha256          内容指纹
     * @return 档案行；不存在时为 null
     */
    @Override
    public WbFileAsset getByKnowledgeFileIdAndSha(Long knowledgeFileId, String sha256) {
        if (NullUtil.isNull(knowledgeFileId) || NullUtil.isNull(sha256)) {
            return null;
        }
        return this.lambdaQuery()
                .eq(WbFileAsset::getKnowledgeFileId, knowledgeFileId)
                .eq(WbFileAsset::getSha256, sha256)
                .one();
    }

    /**
     * 按归属取全部资产档案。
     *
     * @param knowledgeFileId 归属知识库文件
     * @return 档案列表；归属为空时返回空列表
     */
    @Override
    public List<WbFileAsset> listByKnowledgeFileId(Long knowledgeFileId) {
        if (NullUtil.isNull(knowledgeFileId)) {
            return List.of();
        }
        return this.lambdaQuery().eq(WbFileAsset::getKnowledgeFileId, knowledgeFileId).list();
    }
}
