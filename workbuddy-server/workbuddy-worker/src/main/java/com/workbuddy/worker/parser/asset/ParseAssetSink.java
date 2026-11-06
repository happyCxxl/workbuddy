package com.workbuddy.worker.parser.asset;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.ParseAsset;
import com.workbuddy.common.enums.parse.ParseAssetKind;

import java.util.List;

/**
 * 解析资产写入口：解析器把图片字节交给实现，由实现写对象存储并返回资产描述。
 *
 * <p>解析层不直接依赖对象存储；未注入实现时使用 {@link #noop()}，此时图片元素不产生 assetRef。
 */
public interface ParseAssetSink {

    /**
     * 写入一张图片。
     *
     * @param kind     资产类型
     * @param content  图片字节
     * @param mimeType 图片 MIME
     * @param page     文档页（可空）
     * @param bbox     图片区域（可空）
     * @param width    像素宽（未知为 null）
     * @param height   像素高（未知为 null）
     * @return 资产描述；无字节或未启用时返回 null
     */
    ParseAsset put(ParseAssetKind kind, byte[] content, String mimeType, Integer page, BBox bbox,
                   Integer width, Integer height);

    /**
     * 本次解析已写入的资产（按内容去重）。
     *
     * @return 资产清单
     */
    List<ParseAsset> assets();

    /**
     * 是否启用资产写入。
     *
     * @return 启用为 true
     */
    boolean enabled();

    /**
     * 按资产 ID 读回字节（本任务已写入的资产）。
     *
     * @param assetId 资产 ID
     * @return 字节；未登记或实现不保留字节时为 null
     */
    default byte[] read(String assetId) {
        return new byte[0];
    }

    /**
     * 不写资产的口径（单测与未接存储的解析路径）。
     *
     * @return 空实现
     */
    static ParseAssetSink noop() {
        return Noop.INSTANCE;
    }

    /** 空实现：不写资产、不返回 assetRef。 */
    final class Noop implements ParseAssetSink {

        private static final Noop INSTANCE = new Noop();

        private Noop() {
        }

        /**
         * 不写资产。
         *
         * @param kind     资产类型
         * @param content  图片字节
         * @param mimeType 图片 MIME
         * @param page     文档页
         * @param bbox     图片区域
         * @param width    像素宽
         * @param height   像素高
         * @return null
         */
        @Override
        public ParseAsset put(ParseAssetKind kind, byte[] content, String mimeType, Integer page, BBox bbox,
                              Integer width, Integer height) {
            return null;
        }

        /**
         * 无资产。
         *
         * @return 空列表
         */
        @Override
        public List<ParseAsset> assets() {
            return List.of();
        }

        /**
         * 未启用。
         *
         * @return false
         */
        @Override
        public boolean enabled() {
            return false;
        }
    }
}
