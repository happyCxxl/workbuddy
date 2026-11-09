package com.workbuddy.filecenter.asset;

import com.workbuddy.common.domain.entity.WbFileAsset;
import com.workbuddy.common.domain.parse.ParseAsset;
import com.workbuddy.common.utils.JsonUtil;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.db.WbFileAssetDbService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 解析资产档案：登记（按归属 + 内容幂等）、按归属查询、按归属清理。
 *
 * <p>对象本体按内容寻址（sha256）写在对象存储并被不同解析共享；本类只管理档案行，
 * 清理时只删档案、不删对象——同一内容可能仍被其他知识库文件的产物引用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileAssetArchive {

    private final WbFileAssetDbService fileAssetDbService;

    /**
     * 登记资产档案：同归属同内容已存在时直接跳过（唯一键兜底，不重复写）。
     *
     * @param asset           资产描述
     * @param knowledgeFileId 归属知识库文件（一次解析）
     */
    public void register(ParseAsset asset, Long knowledgeFileId) {
        if (NullUtil.isNull(asset) || NullUtil.isNull(knowledgeFileId)
                || NullUtil.isNull(asset.getSha256())) {
            return;
        }
        WbFileAsset existing = fileAssetDbService.getByKnowledgeFileIdAndSha(
                knowledgeFileId, asset.getSha256());
        if (NullUtil.isNotNull(existing)) {
            return;
        }
        WbFileAsset row = new WbFileAsset();
        row.setAssetId(asset.getAssetId());
        row.setKnowledgeFileId(knowledgeFileId);
        row.setKind(NullUtil.isNull(asset.getKind()) ? null : asset.getKind().name());
        row.setPage(asset.getPage());
        row.setBbox(NullUtil.isNull(asset.getBbox()) ? null : JsonUtil.toJsonStr(asset.getBbox()));
        row.setWidth(asset.getWidth());
        row.setHeight(asset.getHeight());
        row.setMimeType(asset.getMimeType());
        row.setFileSize(asset.getSize());
        row.setSha256(asset.getSha256());
        row.setStorageType(asset.getStorageType());
        row.setStorageSourceId(asset.getStorageSourceId());
        row.setBucket(asset.getBucket());
        row.setObjectKey(asset.getObjectKey());
        row.setUrl(asset.getUrl());
        fileAssetDbService.save(row);
    }

    /**
     * 按归属查询资产档案。
     *
     * @param knowledgeFileId 归属知识库文件
     * @return 档案列表（无则空列表）
     */
    public List<WbFileAsset> listByKnowledgeFileId(Long knowledgeFileId) {
        return fileAssetDbService.listByKnowledgeFileId(knowledgeFileId);
    }

    /**
     * 按归属清理档案：只删档案行；对象按内容寻址共享，不在此删除。
     *
     * @param knowledgeFileId 归属知识库文件
     * @return 删除的档案行数
     */
    public int purgeByKnowledgeFileId(Long knowledgeFileId) {
        if (NullUtil.isNull(knowledgeFileId)) {
            return 0;
        }
        List<WbFileAsset> rows = fileAssetDbService.listByKnowledgeFileId(knowledgeFileId);
        if (rows.isEmpty()) {
            return 0;
        }
        fileAssetDbService.removeByIds(rows.stream().map(WbFileAsset::getId).toList());
        return rows.size();
    }
}
