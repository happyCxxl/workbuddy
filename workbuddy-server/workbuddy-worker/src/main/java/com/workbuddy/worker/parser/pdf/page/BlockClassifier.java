package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.detect.TableCandidateDetector;
import com.workbuddy.worker.parser.pdf.model.PageLine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 段落/表格块分类：只决定块边界，不产元素；与旧 {@code flushTextRun} 的边界规则逐条一致。
 */
public final class BlockClassifier {

    /** 段落起始标记：编号条款/中文序号/括号序号/章节/注。 */
    private static final Pattern PARAGRAPH_START = Pattern.compile(
            "^\\s*(?:\\d{1,3}(?:\\.\\d{1,3})+|\\d{1,3}[.、．](?!\\d)|"
                    + "[一二三四五六七八九十]{1,4}、|[（(][一二三四五六七八九十0-9]{1,4}[）)]|"
                    + "第[一二三四五六七八九十0-9]{1,4}[章节部分]|注[：:])");

    private BlockClassifier() {
    }

    /**
     * 把连续行切成有序段落块/表格候选块。
     *
     * @param run 非线框区域内的连续行
     * @param properties 解析配置
     * @return 有序块
     */
    public static List<PageBlock> classify(List<PageLine> run, ParseProperties properties) {
        List<PageBlock> blocks = new ArrayList<>();
        List<PageLine> block = new ArrayList<>();
        List<PageLine> paragraph = new ArrayList<>();
        PageLine prev = null;
        for (PageLine line : run) {
            boolean movedUp = NullUtil.isNotNull(prev) && line.y() < prev.y();
            if (movedUp || (NullUtil.isNotNull(prev)
                    && verticalGap(prev, line) > paragraphGapThreshold(prev, properties))) {
                flushParagraph(blocks, paragraph);
                paragraph = new ArrayList<>();
                if (!block.isEmpty() && (movedUp || !TableCandidateDetector.isCandidate(line))) {
                    blocks.add(new TableBlock(block));
                    block = new ArrayList<>();
                }
            }
            if (!paragraph.isEmpty() && startsParagraphBlock(line.text())) {
                flushParagraph(blocks, paragraph);
                paragraph = new ArrayList<>();
            }
            boolean tocContinuation = line.tocCandidate() && NullUtil.isNotNull(prev)
                    && prev.tocCandidate() && isTocContinuation(line.text());
            if ((line.tocCandidate() || (NullUtil.isNotNull(prev) && prev.tocCandidate()))
                    && !tocContinuation && !paragraph.isEmpty()) {
                flushParagraph(blocks, paragraph);
                paragraph = new ArrayList<>();
            }
            if (TableCandidateDetector.isCandidate(line)) {
                flushParagraph(blocks, paragraph);
                paragraph = new ArrayList<>();
                block.add(line);
            } else {
                if (!block.isEmpty()) {
                    blocks.add(new TableBlock(block));
                    block = new ArrayList<>();
                }
                paragraph.add(line);
            }
            prev = line;
        }
        if (!block.isEmpty()) {
            blocks.add(new TableBlock(block));
        }
        flushParagraph(blocks, paragraph);
        return blocks;
    }

    private static void flushParagraph(List<PageBlock> blocks, List<PageLine> paragraph) {
        if (!paragraph.isEmpty()) {
            blocks.add(new ParagraphBlock(List.copyOf(paragraph)));
        }
    }

    private static boolean startsParagraphBlock(String text) {
        return text != null && PARAGRAPH_START.matcher(text.trim()).find();
    }

    private static boolean isTocContinuation(String text) {
        if (text == null) {
            return true;
        }
        return text.replaceAll("[.．…·\\s\\d第页\\-–—]+", "").isEmpty();
    }

    private static double verticalGap(PageLine upper, PageLine lower) {
        return lower.y() - (upper.y() + upper.height());
    }

    private static double paragraphGapThreshold(PageLine line, ParseProperties properties) {
        return line.fontSize() * properties.getParagraphGapRatio();
    }
}
