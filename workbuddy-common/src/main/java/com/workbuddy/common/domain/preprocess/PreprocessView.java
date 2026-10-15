package com.workbuddy.common.domain.preprocess;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 预处理派生视图（五层）：rawText 永为原文，displayText 供展示，
 * normalizedText 供检索（切片环节 content 口径），normalizedFields 供精确过滤，
 * preprocessTrace 记录命中规则与前后摘要。原文 UnifiedDocument 零改动。
 *
 * @author cxxl
 */
@Data
public class PreprocessView {

    /** 视图 ID = "pv-" + documentId + "-" + strategyVersion（确定性） */
    private String viewId;

    /** 统一文档 ID（UnifiedDocument.documentInfo.documentId） */
    private String documentId;

    /** 文件结果 ID（kb_file_result.id） */
    private Long fileResultId;

    /** 来源文件引用（file-manager 文件 ID） */
    private String sourceFileRef;

    /** 上游产物引用（STRUCTURE 产物 ID） */
    private Long upstreamProductRef;

    /** 策略版本（如 preproc-default-v1） */
    private String strategyVersion;

    /** 策略开关快照（键 = 规则 key 与 规则 key.参数，另含 customRules 条数） */
    private Map<String, String> options;

    /** 派生元素（含 TABLE 的 cells） */
    private List<ViewElement> elements = new ArrayList<>();
}
