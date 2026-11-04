package com.workbuddy.worker.testkit;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseAsset;
import com.workbuddy.common.enums.parse.ParseAssetKind;
import com.workbuddy.common.enums.storage.StorageType;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.asset.ParseAssetSink;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用资产写入口：按内容 sha256 去重，内存保存字节，不依赖对象存储。
 */
public final class RecordingAssetSink implements ParseAssetSink {

    private final Map<String, ParseAsset> assets = new LinkedHashMap<>();

    private final Map<String, byte[]> contents = new LinkedHashMap<>();

    /**
     * 写入资产（同内容复用）。
     *
     * @param kind     资产类型
     * @param content  图片字节
     * @param mimeType 图片 MIME
     * @param page     文档页
     * @param bbox     图片区域
     * @param width    像素宽
     * @param height   像素高
     * @return 资产描述
     */
    @Override
    public ParseAsset put(ParseAssetKind kind, byte[] content, String mimeType, Integer page, BBox bbox,
                          Integer width, Integer height) {
        if (NullUtil.isNull(content) || content.length == 0) {
            return null;
        }
        String sha = sha256(content);
        String assetId = "asset-" + sha;
        ParseAsset existing = assets.get(assetId);
        if (NullUtil.isNotNull(existing)) {
            return existing;
        }
        ParseAsset asset = new ParseAsset();
        asset.setAssetId(assetId);
        asset.setKind(kind);
        asset.setStorageSourceId(1L);
        asset.setStorageType(StorageType.LOCAL.getCode());
        asset.setBucket("test");
        asset.setObjectKey(sha);
        asset.setMimeType(mimeType);
        asset.setSize((long) content.length);
        asset.setSha256(sha);
        asset.setPage(page);
        asset.setBbox(bbox);
        asset.setWidth(width);
        asset.setHeight(height);
        assets.put(assetId, asset);
        contents.put(assetId, content.clone());
        return asset;
    }

    /**
     * 已写入资产。
     *
     * @return 资产清单
     */
    @Override
    public List<ParseAsset> assets() {
        return List.copyOf(assets.values());
    }

    /**
     * 已启用。
     *
     * @return true
     */
    @Override
    public boolean enabled() {
        return true;
    }

    /**
     * 按资产 ID 取字节。
     *
     * @param assetId 资产 ID
     * @return 图片字节；不存在时为 null
     */
    public byte[] bytes(String assetId) {
        byte[] content = contents.get(assetId);
        return NullUtil.isNull(content) ? null : content.clone();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
