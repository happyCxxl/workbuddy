package com.workbuddy.worker.parser.pdf.page;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.FontInfo;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.Provenance;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.pdf.extract.LineTexts;
import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.PdfPageGeometry;

import java.util.List;

/**
 * 段落与页眉/页脚元素组装：只负责把行变成元素，不决定段落边界。
 */
public final class ParagraphAssembler {

    private ParagraphAssembler() {
    }

    /**
     * 段落落地：文本为空/纯空白时不产 PARAGRAPH。
     *
     * @param lines 段落行
     * @param elements 产物元素（追加）
     * @param fileId 文件 ID
     * @param page 单页内容
     * @return 产出的段落元素；空白文本时为 null
     */
    public static ParseElement addParagraph(List<PageLine> lines, List<ParseElement> elements,
                                            String fileId, PageContent page) {
        if (lines.isEmpty()) {
            return null;
        }
        ParseElement paragraph = toParagraphElement(lines, fileId, page);
        if (paragraph.getText() != null && !paragraph.getText().isBlank()) {
            elements.add(paragraph);
            return paragraph;
        }
        return null;
    }

    /**
     * 段落元素组装：多行聚合 + 字体事实（取首行）+ 目录行特征。
     *
     * @param lines 段落行
     * @param fileId 文件 ID
     * @param page 单页内容
     * @return 段落元素
     */
    public static ParseElement toParagraphElement(List<PageLine> lines, String fileId, PageContent page) {
        PageLine first = lines.getFirst();
        PageLine last = lines.getLast();
        ParseElement element = ParseElement.of(ElementType.PARAGRAPH);
        StringBuilder paragraph = new StringBuilder();
        PageLine prevLine = null;
        for (PageLine line : lines) {
            if (NullUtil.isNotNull(prevLine)) {
                paragraph.append(LineTexts.lineSeparator(prevLine, line));
            }
            paragraph.append(line.text());
            prevLine = line;
        }
        element.setText(paragraph.toString());
        element.setPage(first.page());
        double x = lines.stream().mapToDouble(PageLine::x).min().orElse(0);
        double y = first.y();
        double maxX = lines.stream().mapToDouble(l -> l.x() + l.width()).max().orElse(0);
        double maxY = last.y() + last.height();
        element.setBbox(page.geometry().toViewport(x, y, maxX - x, maxY - y));
        element.setDisplayBBox(union(lines.stream()
                .map(line -> visibleLineBBox(line, page.geometry()))
                .toList()));
        FontInfo font = new FontInfo();
        font.setName(first.fontName());
        font.setSize(first.fontSize());
        font.setBold(first.bold());
        element.setFont(font);
        element.setTocCandidate(lines.stream().anyMatch(PageLine::tocCandidate));
        element.setProvenance(new Provenance(fileId, charRangePath(page, lines)));
        return element;
    }

    /**
     * 页眉/页脚元素：文本 + 页码 + 包围盒 + 区域溯源。
     *
     * @param type HEADER / FOOTER
     * @param text 元素文本
     * @param area 区域溯源
     * @param pageNo 页码
     * @param line 来源行
     * @param fileId 文件 ID
     * @param geometry 页几何
     * @return 页眉/页脚元素
     */
    public static ParseElement bandElement(ElementType type, String text, String area,
                                           int pageNo, PageLine line, String fileId,
                                           PdfPageGeometry geometry) {
        ParseElement element = ParseElement.of(type);
        element.setText(text);
        element.setPage(pageNo);
        element.setBbox(geometry.toViewport(line.x(), line.y(), line.width(), line.height()));
        element.setDisplayBBox(visibleLineBBox(line, geometry));
        FontInfo font = new FontInfo();
        font.setName(line.fontName());
        font.setSize(line.fontSize());
        font.setBold(line.bold());
        element.setFont(font);
        element.setProvenance(new Provenance(fileId, area));
        return element;
    }

    /** 可见字形行框。 */
    private static BBox visibleLineBBox(PageLine line, PdfPageGeometry geometry) {
        List<CharInfo> visible = line.chars().stream()
                .filter(ch -> !Character.isWhitespace(ch.codePoint()))
                .toList();
        if (visible.isEmpty()) {
            double ascent = Math.max(line.height(), line.fontSize() * 0.8);
            return geometry.toViewport(line.x(), line.y() - ascent,
                    line.width(), ascent);
        }
        double minX = visible.stream().mapToDouble(CharInfo::x).min().orElse(line.x());
        double maxX = visible.stream().mapToDouble(ch -> ch.x() + ch.width()).max()
                .orElse(line.x() + line.width());
        double ascent = Math.max(line.height(), line.fontSize() * 0.8);
        double minY = visible.stream().mapToDouble(CharInfo::y).min().orElse(line.y()) - ascent;
        double maxY = visible.stream().mapToDouble(CharInfo::y).max()
                .orElse(line.y() + line.height());
        return geometry.toViewport(minX, minY, maxX - minX, maxY - minY);
    }

    /** 多个视口矩形取并集。 */
    private static BBox union(List<BBox> boxes) {
        double minX = boxes.stream().mapToDouble(BBox::getX).min().orElse(0);
        double minY = boxes.stream().mapToDouble(BBox::getY).min().orElse(0);
        double maxX = boxes.stream().mapToDouble(box -> box.getX() + box.getWidth()).max().orElse(0);
        double maxY = boxes.stream().mapToDouble(box -> box.getY() + box.getHeight()).max().orElse(0);
        return new BBox(minX, minY, maxX - minX, maxY - minY);
    }

    private static String charRangePath(PageContent page, List<PageLine> lines) {
        String head = "pdf#page(" + page.pageNo() + ")";
        PageLine last = lines.getLast();
        int start = charOffset(page, lines.getFirst());
        int end = charOffset(page, last);
        if (start < 0 || end < 0) {
            return head;
        }
        return head + "/chars[" + start + ".." + (end + last.chars().size() - 1) + "]";
    }

    /** 行在本页字符流中的起始偏移。 */
    private static int charOffset(PageContent page, PageLine target) {
        int offset = 0;
        for (PageLine line : page.lines()) {
            if (line == target) {
                return offset;
            }
            offset += line.chars().size();
        }
        return -1;
    }
}
