package com.workbuddy.common.domain.parse;

import com.workbuddy.common.domain.input.FileReference;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析结果封装（环节最终产物，落产物存储的 JSON 本体）。
 * 按来源分组多路、不合并；合并裁决归组装环节。
 *
 * @author cxxl
 */
@Data
public class ParseResult {

    /** 文件结果 ID（血缘起点） */
    private Long resultId;

    /** 输入文件引用（fileId/fileName/sha256/mimeType/pageCount） */
    private FileReference file;

    /** 本次解析实际使用的能力快照（可复现） */
    private CapabilitySnapshot capabilitySnapshot;

    /** 按来源分组的结果（当前 native 为主；ocr 路留空 note 占位） */
    private List<ParseSource> sources = new ArrayList<>();

    /** 质量信息（告警只标记不阻断） */
    private QualityInfo quality;
}
