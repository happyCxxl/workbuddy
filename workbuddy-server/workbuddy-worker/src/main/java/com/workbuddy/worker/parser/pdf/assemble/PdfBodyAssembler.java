package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.FontInfo;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.Provenance;
import com.workbuddy.common.domain.parse.capability.PageImage;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.capability.CapabilityRegistry;
import com.workbuddy.worker.parser.capability.LayoutCapabilityAttempt;
import com.workbuddy.worker.parser.pdf.detect.HeaderFooterDetector;
import com.workbuddy.worker.parser.pdf.detect.RuleRegions;
import com.workbuddy.worker.parser.pdf.detect.TableCandidateDetector;
import com.workbuddy.worker.parser.pdf.extract.LineTexts;
import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.PageLines;
import com.workbuddy.worker.parser.pdf.model.PdfPageGeometry;
import com.workbuddy.worker.parser.pdf.model.Region;
import com.workbuddy.worker.parser.pdf.model.TextLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 单页正文装配：页眉/页脚元素（每页各一条）→ 线框区域分段 → 段落聚合与表格候选块结算；
 * 表格结算委托 {@link TextTableBuilder}（无边框）与 {@link RuledTableBuilder}（有边框）。
 */
public final class PdfBodyAssembler {

    /** 段落起始标记：编号条款/中文序号/括号序号/章节/注；命中即与上一段断开 */
    private static final Pattern PARAGRAPH_START = Pattern.compile(
            "^\\s*(?:\\d{1,3}(?:\\.\\d{1,3})+|\\d{1,3}[.、．](?!\\d)|"
                    + "[一二三四五六七八九十]{1,4}、|[（(][一二三四五六七八九十0-9]{1,4}[）)]|"
                    + "第[一二三四五六七八九十0-9]{1,4}[章节部分]|注[：:])");

    private PdfBodyAssembler() {
    }

    /**
     * 复杂页的版面能力兜底：栏数达到门槛才问模型阅读顺序，拿到顺序就按模型区域重排正文行序，
     * 并把能力引用与留痕事实记到本路；未接入、非复杂页或结果不可用都保持几何顺序不变。
     */
    private static List<PageLine> applyLayoutCapability(List<PageLine> bodyLines, PageContent page,
                                                        ParseSource source, ParseProperties properties,
                                                        CapabilityRegistry capabilityRegistry) {
        if (NullUtil.isNull(capabilityRegistry) || !LayoutComplexity.isComplex(bodyLines, properties)) {
            return bodyLines;
        }
        PageImage image = new PageImage();
        image.setPage(page.pageNo());
        image.setWidth(page.pageWidth());
        image.setHeight(page.pageHeight());
        LayoutCapabilityAttempt.Outcome outcome =
                LayoutCapabilityAttempt.attempt(capabilityRegistry, image, page.pageNo());
        source.getFacts().add(outcome.fact());
        if (!outcome.enhanced()) {
            return bodyLines;
        }
        source.getCapabilities().add(outcome.usage());
        return LayoutOrderSupport.reorder(bodyLines, outcome.regions(), outcome.order());
    }

    /**
     * 单页元素组装：页眉/页脚元素（每页各一条）转正文按 y 顺序推进，表格候选块与段落互斥结算。
     *
     * @param page               单页提取内容
     * @param headers            已确认的页眉文本键
     * @param footers            已确认的页脚键
     * @param elements           产物元素（追加）
     * @param source             解析结果
     * @param fileId             文件 ID
     * @param properties         解析阈值配置
     * @param capabilityRegistry 能力注册表
     */
    public static void assemble(PageContent page, Set<String> headers, HeaderFooterDetector.FooterKeys footers,
                         List<ParseElement> elements, ParseSource source, String fileId,
                         ParseProperties properties, CapabilityRegistry capabilityRegistry) {
        double headerBandY = page.pageHeight() * properties.getHeaderAreaRatio();
        double footerBandY = page.pageHeight() * (1 - properties.getFooterAreaRatio());
        PageLines pageLines = PageLines.of(page);

        // 页首 HEADER 元素：本页页眉带内命中键的行各产一条（文本取原行）
        for (PageLine line : page.lines()) {
            String key = HeaderFooterDetector.normalizeKey(line.text());
            if (line.y() < headerBandY && headers.contains(key)) {
                elements.add(bandElement(
                        ElementType.HEADER, line.text().trim(), "pdf#top-area", page.pageNo(), line, fileId,
                        page.geometry()));
            }
        }

        // 页底 FOOTER 元素：本页页底带内命中页脚键或页码位置的行各产一条（文本取原行）；
        // 先暂存，正文结算后追加，保证元素顺序是页眉 → 正文 → 页脚
        List<ParseElement> footerElements = new ArrayList<>();
        for (PageLine line : page.lines()) {
            if (line.y() + line.height() < footerBandY) {
                continue;
            }
            String footerKey = HeaderFooterDetector.footerBase(HeaderFooterDetector.normalizeKey(line.text()));
            if (footers.matches(footerKey, TextLine.of(line), pageLines, properties)) {
                footerElements.add(bandElement(
                        ElementType.FOOTER, line.text().trim(), "pdf#bottom-area", page.pageNo(), line, fileId,
                        page.geometry()));
            }
        }

        // 正文：页眉带内的命中行与页底带内的页脚行不进正文（内容已由上面的元素承载）；
        // 顺序沿用页内阅读顺序（分栏页已按栏排好）
        List<PageLine> bodyLines = page.lines().stream()
                .filter(line -> line.y() >= headerBandY
                        || !headers.contains(HeaderFooterDetector.normalizeKey(line.text())))
                .filter(line -> {
                    if (line.y() + line.height() < footerBandY) {
                        return true;
                    }
                    String footerKey = HeaderFooterDetector.footerBase(
                            HeaderFooterDetector.normalizeKey(line.text()));
                    return !footers.matches(footerKey, TextLine.of(line), pageLines, properties);
                })
                .toList();

        // 线框区域：区域内行归线网格表格，其余行按段落与文本表格候选推进
        List<Region> regions = RuleRegions.regions(page.segments(), properties.getTableLineCoverRatio());
        List<PageLine> run = new ArrayList<>();
        Region runRegion = null;
        for (PageLine line : applyLayoutCapability(bodyLines, page, source, properties, capabilityRegistry)) {
            Region region = regionOf(regions, line);
            if (region != runRegion) {
                flushRun(run, runRegion, page, elements, source, fileId, properties, capabilityRegistry);
                run = new ArrayList<>();
                runRegion = region;
            }
            run.add(line);
        }
        flushRun(run, runRegion, page, elements, source, fileId, properties, capabilityRegistry);
        elements.addAll(footerElements);
    }

    /** 行归属的线框区域：行中心落在区域内即归属；无归属返回 null */
    private static Region regionOf(List<Region> regions, PageLine line) {
        double centerX = line.x() + line.width() / 2;
        double centerY = line.y() + line.height() / 2;
        for (Region region : regions) {
            if (region.contains(centerX, centerY)) {
                return region;
            }
        }
        return null;
    }

    /** 结算一段连续行：线框区域走线网格表格，其余行走段落与文本表格候选 */
    private static void flushRun(List<PageLine> run, Region region, PageContent page,
                                 List<ParseElement> elements, ParseSource source, String fileId,
                                 ParseProperties properties, CapabilityRegistry capabilityRegistry) {
        if (run.isEmpty()) {
            return;
        }
        if (NullUtil.isNotNull(region)) {
            RuledTableBuilder.flush(run, region, page, elements, source, fileId, properties, capabilityRegistry);
            return;
        }
        flushTextRun(run, page, elements, source, fileId, properties, capabilityRegistry);
    }

    /** 无边框路径：段落聚合 + 文本表格候选块（行距超阈值结算段落；候选行连续成块） */
    static void flushTextRun(List<PageLine> run, PageContent page, List<ParseElement> elements,
                             ParseSource source, String fileId, ParseProperties properties,
                             CapabilityRegistry capabilityRegistry) {
        List<PageLine> block = new ArrayList<>();
        List<PageLine> paragraph = new ArrayList<>();
        PageLine prev = null;
        for (PageLine line : run) {
            // 行序回跳 = 进入下一栏或下一带：段落与表格块都不跨栏、不跨带
            boolean movedUp = NullUtil.isNotNull(prev) && line.y() < prev.y();
            if (movedUp || (NullUtil.isNotNull(prev)
                    && verticalGap(prev, line) > paragraphGapThreshold(prev, properties))) {
                // 行距超阈值：先结算段落；表格候选块允许更大行距（表格行距可达数倍行高），
                // 仅当新行不再具备宽列距（非表格候选）时才结算表格块
                if (!paragraph.isEmpty()) {
                    addParagraph(paragraph, elements, fileId, page);
                    paragraph = new ArrayList<>();
                }
                if (!block.isEmpty() && (movedUp || !TableCandidateDetector.isCandidate(line))) {
                    TextTableBuilder.flush(block, page, elements, source, fileId, properties, capabilityRegistry);
                    block = new ArrayList<>();
                }
            }
            // 编号/章节/注等段落起始标记：即使行距未超阈值，也在这里断段
            if (!paragraph.isEmpty() && startsParagraphBlock(line.text())) {
                addParagraph(paragraph, elements, fileId, page);
                paragraph = new ArrayList<>();
            }
            // 目录行按条独立成段：点线目录的行距小于普通段落阈值，不能与相邻条目聚合；
            // 只含点线/数字的续行仍并入上一条目，避免把一条目录拆成两段
            boolean tocContinuation = line.tocCandidate() && NullUtil.isNotNull(prev)
                    && prev.tocCandidate() && isTocContinuation(line.text());
            if ((line.tocCandidate() || (NullUtil.isNotNull(prev) && prev.tocCandidate()))
                    && !tocContinuation && !paragraph.isEmpty()) {
                addParagraph(paragraph, elements, fileId, page);
                paragraph = new ArrayList<>();
            }
            if (TableCandidateDetector.isCandidate(line)) {
                if (!paragraph.isEmpty()) {
                    addParagraph(paragraph, elements, fileId, page);
                    paragraph = new ArrayList<>();
                }
                block.add(line);
            } else {
                if (!block.isEmpty()) {
                    TextTableBuilder.flush(block, page, elements, source, fileId, properties, capabilityRegistry);
                    block = new ArrayList<>();
                }
                paragraph.add(line);
            }
            prev = line;        }
        if (!block.isEmpty()) {
            TextTableBuilder.flush(block, page, elements, source, fileId, properties, capabilityRegistry);
        }
        if (!paragraph.isEmpty()) {
            addParagraph(paragraph, elements, fileId, page);
        }
    }

    /** 是否段落起始标记：编号/章节/注等，行距不足时也用于断段 */
    private static boolean startsParagraphBlock(String text) {
        return text != null && PARAGRAPH_START.matcher(text.trim()).find();
    }

    /** 目录续行：去掉点线/数字/页码字后没有正文，视为同一条目的续行 */
    private static boolean isTocContinuation(String text) {
        if (text == null) {
            return true;
        }
        return text.replaceAll("[.．…·\\s\\d第页\\-–—]+", "").isEmpty();
    }

    /** 行间距（下行的上边 - 上行的下边） */
    private static double verticalGap(PageLine upper, PageLine lower) {
        return lower.y() - (upper.y() + upper.height());
    }

    /** 段落聚合的行距阈值（字号 × 阈值比例） */
    private static double paragraphGapThreshold(PageLine line, ParseProperties properties) {
        return line.fontSize() * properties.getParagraphGapRatio();
    }

    /**
     * 段落落地：文本为空/纯空白时不产 PARAGRAPH，避免详情出现“（无文本）”空段落。
     *
     * @param lines    段落行
     * @param elements 产物元素（追加）
     * @param fileId   文件 ID
     * @param page     单页内容
     * @return 产出的段落元素；空白文本时为 null
     */
    static ParseElement addParagraph(List<PageLine> lines, List<ParseElement> elements,
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
     * 段落元素组装：多行聚合 + 字体事实（取首行）+ 目录行特征；ID 由 {@link PageElementIds} 补，
     * 溯源回指本页字符流区间。
     */
    static ParseElement toParagraphElement(List<PageLine> lines, String fileId, PageContent page) {
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
     * 可见字形行框：过滤行首/行尾空白字符，只取实际字符的包围盒。
     * CharInfo.y 是 PDFBox 的基线（YDirAdj），上沿要减去字形 ascent；
     * 字高通常只有字号的一半，直接用 height 会仍偏低，按字号 0.8 估算 ascent。
     * 字符前进宽度也会把缩进/尾部空白算进高亮，逐行贴合必须收紧到字形范围。
     */
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

    /** 多个视口矩形取并集 */
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

    /** 行在本页字符流中的起始偏移；行不在本页时返回 -1（按实例比对：内容相同的行也可各自算偏移） */
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

    /**
     * 页眉/页脚元素：文本 + 页码 + 包围盒 + 区域溯源（页首/页底装配口径一致，只差区域与元素类型）。
     *
     * @param type   HEADER / FOOTER
     * @param text   元素文本
     * @param area   区域溯源（页首区域 / 页底区域）
     * @param pageNo   页码
     * @param line     来源行（取包围盒）
     * @param fileId   文件 ID
     * @param geometry 页几何（版面坐标 → 视口坐标）
     */
    private static ParseElement bandElement(ElementType type, String text, String area,
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
}
