package com.workbuddy.common.domain.structure;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一文档模型：组装环节把多路解析结果焊接成的知识库内部唯一稳定结构。
 * 下游环节只读、永不修改；schema 变更走版本（{@link #SCHEMA_VERSION}）——新增字段保持向后兼容，
 * 破坏性变更递增版本号并同步下游兼容处理。
 *
 * @author cxxl
 */
@Data
public class UnifiedDocument {

    /** 统一文档 schema 版本（DocumentInfo.schemaVersion 与产物能力快照共用的单一事实源） */
    public static final String SCHEMA_VERSION = "1.0.0";

    /** 文档信息（血缘起点 + schema 版本 + 能力快照） */
    private DocumentInfo documentInfo;

    /** 页面基准列表（XLS/XLSX 为空） */
    private List<UnifiedPage> pages = new ArrayList<>();

    /** 元素列表 */
    private List<UnifiedElement> elements = new ArrayList<>();

    /** 关系列表（PARENT_CHILD/NEXT/TABLE_CELL_OF/CONTINUATION_OF/…） */
    private List<DocumentRelation> relations = new ArrayList<>();

    /** 二进制资源引用（预留未实现：当前无写入方，启用需解析侧产出资源引用后在此登记） */
    private List<DocumentAsset> assets = new ArrayList<>();

    /** 质量（warnings/conflicts，只标记不阻断） */
    private DocumentQuality quality = new DocumentQuality();
}
