package com.workbuddy.common.domain.parse;

import com.workbuddy.common.enums.parse.UnitType;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析产物摘要：计数、失败页、本次阈值快照与配置哈希，随 ParseResult 一起落产物。
 * 用于回答“这份产物为什么是该状态”，不替代 sources/quality 明细。
 */
@Data
public class ParseSummary {

    /** 判定单元总数（PDF 页数 / Excel sheet 数 / Word 虚拟页数） */
    private int unitCount;

    /** 判定单元类型（PAGE/SLIDE/SHEET/VIRTUAL_PAGE），与 unitCount 配对，供展示与统计口径 */
    private UnitType unitType;

    /** 可读单元数（内容单元 − 失败单元，下限 0） */
    private int readableUnits;

    /** 失败单元数 */
    private int failedUnits;

    /** 空白页数（从分母剔除） */
    private int blankUnits;

    /** 扫描页数（OCR 未覆盖与已覆盖的并集） */
    private int scannedUnits;

    /** OCR 路产出文字的页数 */
    private int ocrPages;

    /** OCR 覆盖的扫描页数 */
    private int ocrRecoveredUnits;

    /** 标记为降级的元素数（各 source 合计） */
    private int degradedElements;

    /** 图片资产数（按内容去重） */
    private int assetCount;

    /** 失败页/单元页码（去重升序） */
    private List<Integer> failedPages = new ArrayList<>();

    /** 本次判定使用的关键阈值快照（kebab-case 键） */
    private Map<String, Object> thresholds = new LinkedHashMap<>();

    /** 全量 ParseProperties 序列化后的 SHA-256（十六进制） */
    private String configHash;
}
