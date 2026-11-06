package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.worker.parser.pdf.model.PageLine;

import java.util.List;

/**
 * 文本表格候选块：连续表格候选行。
 *
 * @param lines 候选行
 */
public record TableBlock(List<PageLine> lines) implements PageBlock {
}
