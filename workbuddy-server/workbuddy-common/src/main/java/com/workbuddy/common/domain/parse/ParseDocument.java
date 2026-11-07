package com.workbuddy.common.domain.parse;

import com.workbuddy.common.domain.parse.capability.CapabilityUsage;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.enums.parse.UnitType;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析文档结果：一路解析的单元集合（内容 + 位置 + 单元质量）与路级事实。
 * 解析只回答「这一单元有什么、在哪里、质量如何」；跨单元/文档级判断归组装。
 */
@Data
public class ParseDocument {

    /** 具体解析器/提供方（如 pdfbox-3.0.4） */
    private String provider;

    /** 单元类型（同一文档内一致） */
    private UnitType unitType;

    /** 单元集合（按文档序） */
    private List<ParseUnitResult> units = new ArrayList<>();

    /** 解析期发现的事实信号（结构缺口/表格规则失败等） */
    private List<ParseFact> facts = new ArrayList<>();

    /** 本路实际用到的模型能力 */
    private List<CapabilityUsage> capabilities = new ArrayList<>();
}
