package com.workbuddy.biz.task.runner.asset;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseAsset;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.parse.ParseAssetKind;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.storage.FileStorage;
import com.workbuddy.worker.parser.asset.ParseAssetException;
import com.workbuddy.worker.parser.asset.ParseAssetSink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析资产写入口的对象存储实现：按内容寻址写入，同一任务内相同字节只保留一份资产描述。
 */
public class FileStorageParseAssetSink implements ParseAssetSink {

    private static final String ASSET_ID_PREFIX = "asset-";

    private final FileStorage fileStorage;

    private final Map<String, ParseAsset> assets = new LinkedHashMap<>();

    /**
     * 构造资产写入实现。
     *
     * @param fileStorage 对象存储
     */
    public FileStorageParseAssetSink(FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    /**
     * 写入图片字节并登记资产。
     *
     * @param kind     资产类型
     * @param content  图片字节
     * @param mimeType 图片 MIME
     * @param page     文档页
     * @param bbox     图片区域
     * @param width    像素宽
     * @param height   像素高
     * @return 资产描述；空白字节返回 null
     */
    @Override
    public ParseAsset put(ParseAssetKind kind, byte[] content, String mimeType, Integer page, BBox bbox,
                          Integer width, Integer height) {
        if (NullUtil.isNull(content) || content.length == 0) {
            return null;
        }
        try {
            ObjectRef ref = fileStorage.putObject(content);
            String assetId = ASSET_ID_PREFIX + ref.objectKey();
            ParseAsset existing = assets.get(assetId);
            if (NullUtil.isNotNull(existing)) {
                return existing;
            }
            ParseAsset asset = new ParseAsset();
            asset.setAssetId(assetId);
            asset.setKind(kind);
            asset.setStorageSourceId(ref.sourceId());
            asset.setStorageType(ref.storageType().getCode());
            asset.setBucket(ref.bucket());
            asset.setObjectKey(ref.objectKey());
            asset.setMimeType(mimeType);
            asset.setSize((long) content.length);
            asset.setSha256(ref.objectKey());
            asset.setPage(page);
            asset.setBbox(bbox);
            asset.setWidth(width);
            asset.setHeight(height);
            assets.put(assetId, asset);
            return asset;
        } catch (RuntimeException e) {
            throw new ParseAssetException("解析资产写入失败: " + mimeType, e);
        }
    }

    /**
     * 本次解析已写入的资产。
     *
     * @return 资产清单（按首次写入顺序）
     */
    @Override
    public List<ParseAsset> assets() {
        return List.copyOf(new ArrayList<>(assets.values()));
    }

    /**
     * 已接对象存储。
     *
     * @return true
     */
    @Override
    public boolean enabled() {
        return true;
    }
}
