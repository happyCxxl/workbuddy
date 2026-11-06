package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.worker.parser.pdf.model.PageLine;

import java.util.List;

/**
 * 段落块：连续的非表格候选行。
 *
 * @param lines 段落行
 */
public record ParagraphBlock(List<PageLine> lines) implements PageBlock {
}
