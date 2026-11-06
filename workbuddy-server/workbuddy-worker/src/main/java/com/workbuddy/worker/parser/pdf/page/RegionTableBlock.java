package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.Region;

import java.util.List;

/**
 * 线框区域表格块：同一区域只在首次出现时结算一次。
 *
 * @param region 线框区域
 * @param lines 区域内行（按 y 排序）
 */
public record RegionTableBlock(Region region, List<PageLine> lines) implements PageBlock {
}
