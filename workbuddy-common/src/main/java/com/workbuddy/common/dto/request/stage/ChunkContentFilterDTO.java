package com.workbuddy.common.dto.request.stage;

/**
 * 切片内容过滤条件（产物内容接口 CHUNK 专用）：三项都可空，空表示不过滤。
 *
 * @param contentType 内容类型（ChunkContentType 枚举名，可空）
 * @param fallback    是否只取兜底片：true 只要兜底片、false 排除兜底片、空不过滤
 * @param hasParent   是否有父片：true 只要子片、false 只要无父片的片（父片与孤儿片）、空不过滤
 */
public record ChunkContentFilterDTO(String contentType, Boolean fallback, Boolean hasParent) {

    /** 不过滤 */
    public static ChunkContentFilterDTO none() {
        return new ChunkContentFilterDTO(null, null, null);
    }
}
