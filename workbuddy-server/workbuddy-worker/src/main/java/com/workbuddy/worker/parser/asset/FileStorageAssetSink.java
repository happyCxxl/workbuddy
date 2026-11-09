package com.workbuddy.worker.parser.asset;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseAsset;
import com.workbuddy.common.domain.storage.ObjectRef;
import com.workbuddy.common.enums.parse.ParseAssetKind;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.filecenter.asset.FileAssetArchive;
import com.workbuddy.filecenter.storage.FileStorage;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析资产写入实现：调 file 模块的通用对象写入，按内容寻址（sha256）去重，并登记资产档案。
 *
 * <p>解析语义（资产类型、页、区域、尺寸）留在本类组装；存储位置由本类决定
 * （{@code parse-assets/} 前缀），file 模块只负责按位置读写。
 */
@Slf4j
public class FileStorageAssetSink implements ParseAssetSink {

    private static final String ASSET_ID_PREFIX = "asset-";

    private static final String KEY_PREFIX = "parse-assets/";

    private final FileStorage fileStorage;

    private final FileAssetArchive fileAssetArchive;

    private final Long knowledgeFileId;

    private final Map<String, ParseAsset> assets = new LinkedHashMap<>();

    /** 资产 ID → 对象位置（写入时登记，读回按位置取） */
    private final Map<String, ObjectRef> refs = new LinkedHashMap<>();

    /**
     * 构造资产写入实现。
     *
     * @param fileStorage      对象存储（file 模块）
     * @param fileAssetArchive 资产档案（file 模块）
     * @param knowledgeFileId  归属知识库文件（一次解析）
     */
    public FileStorageAssetSink(FileStorage fileStorage, FileAssetArchive fileAssetArchive,
                                Long knowledgeFileId) {
        this.fileStorage = fileStorage;
        this.fileAssetArchive = fileAssetArchive;
        this.knowledgeFileId = knowledgeFileId;
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
            String sha256 = sha256Hex(content);
            String assetId = ASSET_ID_PREFIX + sha256;
            ParseAsset existing = assets.get(assetId);
            if (NullUtil.isNotNull(existing)) {
                return existing;
            }
            ObjectRef ref = fileStorage.putRaw(KEY_PREFIX + sha256, new ByteArrayInputStream(content),
                    content.length, mimeType);
            ParseAsset asset = new ParseAsset();
            asset.setAssetId(assetId);
            asset.setKind(kind);
            asset.setStorageSourceId(ref.sourceId());
            asset.setStorageType(ref.storageType().getCode());
            asset.setBucket(ref.bucket());
            asset.setObjectKey(ref.objectKey());
            asset.setUrl(fileStorage.publicUrl(ref));
            asset.setMimeType(mimeType);
            asset.setSize((long) content.length);
            asset.setSha256(sha256);
            asset.setPage(page);
            asset.setBbox(bbox);
            asset.setWidth(width);
            asset.setHeight(height);
            assets.put(assetId, asset);
            refs.put(assetId, ref);
            registerArchive(asset);
            return asset;
        } catch (RuntimeException e) {
            throw new ParseAssetException("解析资产写入失败: " + mimeType, e);
        }
    }

    /** 档案登记：失败只告警，不阻断解析（对象已写入，产物可用；档案可由后续运维补齐）。 */
    private void registerArchive(ParseAsset asset) {
        try {
            fileAssetArchive.register(asset, knowledgeFileId);
        } catch (RuntimeException e) {
            log.warn("===> FileStorageAssetSink 资产档案登记失败, assetId={}, error={}",
                    asset.getAssetId(), e.getMessage());
        }
    }

    /** 内容指纹：sha256（十六进制小写）。 */
    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
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

    /**
     * 按资产 ID 读回字节。
     *
     * @param assetId 资产 ID
     * @return 字节；未登记为 null
     */
    @Override
    public byte[] read(String assetId) {
        ObjectRef ref = refs.get(assetId);
        return NullUtil.isNull(ref) ? null : fileStorage.getObject(ref);
    }
}
