package com.workbuddy.common.dto.response.stagecontent;

import lombok.Data;

/**
 * 产物内容项（通用化，前端按环节渲染）：
 * 对齐键 alignKey——PARSE/STRUCTURE/PREPROCESS=elementId（跨运行稳定）；CHUNK/EMBED=顺序号（chunkId 跨策略不承诺稳定）。
 */
@Data
public class StageContentItemVO {

    /** 对齐键（跨运行内容层行对齐用） */
    private String alignKey;

    /** 集合内顺序（渲染行序） */
    private Integer seq;

    /** 元素/片类型（ElementType/UnifiedElementType/ChunkContentType 枚举名） */
    private String type;

    /** 状态（预处理元素状态/向量记录状态/组装冲突标记等） */
    private String status;

    /** 展示文本（原样；可能大段，前端截断） */
    private String display;

    /** 检索文本（预处理环节用；其余环节与 display 相同或为空） */
    private String normalized;

    /** 环节专属：解析=source/page/rows/cols 等；组装=level/conflictStatus；
     *  预处理=normalizedFields/cells；切片=titlePath/charCount/tokenCount/parentChunkId；
     *  向量化=cacheHit/tokenCount/requestId/chunkId；字段清单见 {@link StageContentExtraVO} */
    private StageContentExtraVO extra;
}
