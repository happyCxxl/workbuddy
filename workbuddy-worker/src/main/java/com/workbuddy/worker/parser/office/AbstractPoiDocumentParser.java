package com.workbuddy.worker.parser.office;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.parse.SignalType;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.common.utils.TextUtil;
import com.workbuddy.worker.parser.DocumentParserPort;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.support.ParserStreamSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * POI 系解析器基类（非 Spring Bean）：能力标识、parse 骨架
 * （读全输入 → 建 native 源 → parseNative）与解析事实组装；输入流读取见 {@link ParserStreamSupport}。
 */
public abstract class AbstractPoiDocumentParser implements DocumentParserPort {

    private static final String CAPABILITY = "poi";
    private static final String CAPABILITY_VERSION = "5.4.0";

    @Override
    public final ParseSource parse(ParseContext context) {
        byte[] data = ParserStreamSupport.readAll(context);
        ParseSource source = ParseSource.nativeSource(capabilityName() + "-" + capabilityVersion());
        parseNative(source, data, context);
        return source;
    }

    /**
     * 原生结构解析主流程（输入已读全、source 已带能力标识）。
     *
     * @param source  待填充的原生解析结果
     * @param data    文件字节（已读全）
     * @param context 解析上下文（文件引用等）
     */
    protected abstract void parseNative(ParseSource source, byte[] data, ParseContext context);

    /** 表格元素追加单元格（cells 惰性建表，各格式表格单元格统一入口） */
    protected static void appendCell(ParseElement tableElement, ParseElement cellElement) {
        if (NullUtil.isNull(tableElement.getCells())) {
            tableElement.setCells(new ArrayList<>());
        }
        tableElement.getCells().add(cellElement);
    }

    /**
     * 表头行判定（各格式统一口径）：首行存在非空文本单元格即判第 0 行为表头行；首行全空时表头行为 null。
     * 同时把单元格的 `isHeader` 收敛为该判定（有表头时仅第 0 行置真）。
     *
     * @param tableElement 表格元素（单元格已追加完毕）
     * @return 表头行号或 null
     */
    public static Integer settleHeaderRow(ParseElement tableElement) {
        List<ParseElement> cells = ObjectUtil.defaultIfNull(tableElement.getCells(), List.of());
        boolean hasHeader = cells.stream()
                .filter(cell -> Integer.valueOf(0).equals(cell.getRow()))
                .anyMatch(cell -> StrUtil.isNotBlank(cell.getText()));
        Integer headerRow = hasHeader ? 0 : null;
        for (ParseElement cell : cells) {
            cell.setIsHeader(NullUtil.isNotNull(headerRow) && Integer.valueOf(0).equals(cell.getRow()));
        }
        return headerRow;
    }

    @Override
    public String capabilityName() {
        return CAPABILITY;
    }

    @Override
    public String capabilityVersion() {
        return CAPABILITY_VERSION;
    }

    /** 组装嵌入图片解析事实信号（OCR_IMAGE，图片仅引用无文字，由管线判定器汇总）。 */
    protected ParseFact fact(String region, String evidence) {
        ParseFact fact = new ParseFact();
        fact.setType(SignalType.OCR_IMAGE);
        fact.setRegion(region);
        fact.setEvidence(evidence);
        return fact;
    }

    /**
     * Word 系单元指标：整档正文按 pageChars 折算虚拟页（页单位口径），逐单元回填字符数、乱码率与内嵌图片数；
     * 图片按它在正文里的**位置**归属当前单元（文本先推进、遇到图片计入当前单元），不再一律记在首个单元。
     */
    protected void fillVirtualPageMetrics(ParseSource source, int pageChars) {
        List<String> pages = new ArrayList<>();
        List<Integer> imageCounts = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        int budget = 0;
        int pendingImages = 0;
        for (ParseElement element : budgetElements(source)) {
            if (ElementType.IMAGE.name().equals(element.getType())) {
                pendingImages++;
                continue;
            }
            String elementText = budgetText(element);
            for (int i = 0; i < elementText.length(); ) {
                int codePoint = elementText.codePointAt(i);
                page.appendCodePoint(codePoint);
                if (!Character.isWhitespace(codePoint) && ++budget >= pageChars) {
                    pages.add(page.toString());
                    imageCounts.add(pendingImages);
                    pendingImages = 0;
                    page = new StringBuilder();
                    budget = 0;
                }
                i += Character.charCount(codePoint);
            }
        }
        if (!page.isEmpty() || pages.isEmpty()) {
            pages.add(page.toString());
            imageCounts.add(pendingImages);
        }
        fillUnitMetrics(source, pages, imageCounts);
    }

    /** 逐单元指标回填（Excel 每 worksheet = 1 单元）：单元数即判定单元数 */
    protected void fillUnitMetrics(ParseSource source, List<String> unitTexts, List<Integer> unitImageCounts) {
        List<PageMetric> metrics = new ArrayList<>();
        for (int i = 0; i < unitTexts.size(); i++) {
            String text = StrUtil.blankToDefault(unitTexts.get(i), "");
            PageMetric metric = new PageMetric();
            metric.setPage(i + 1);
            metric.setCharCount(nonBlankCount(text));
            metric.setGarbledRatio(TextUtil.garbledRatio(text));
            metric.setImageCount(i < unitImageCounts.size() ? unitImageCounts.get(i) : 0);
            metrics.add(metric);
        }
        source.setPageMetrics(metrics);
        source.setUnitCount(metrics.size());
    }

    /**
     * 单元预算元素：全档元素按产出顺序，**页眉页脚也算进预算**（口径：预算覆盖整档可见文字，
     * 不只正文），图片元素保留占位以便按位置归属单元。
     */
    protected static List<ParseElement> budgetElements(ParseSource source) {
        return source.getElements();
    }

    /**
     * 单元预算文本：段落取自身文本；表格取自身文本 + **各单元格文本**（单元格在嵌套 `cells` 里，
     * 只取表格元素自身会漏掉整张表的文字）；页眉页脚元素取自身文本。
     */
    protected static String budgetText(ParseElement element) {
        StringBuilder text = new StringBuilder(StrUtil.blankToDefault(element.getText(), ""));
        List<ParseElement> cells = NullUtil.isNull(element.getCells()) ? List.of() : element.getCells();
        for (ParseElement cell : cells) {
            text.append(StrUtil.blankToDefault(cell.getText(), ""));
        }
        return text.toString();
    }

    /** 嵌入图片元素数 */
    protected static int imageCount(ParseSource source) {
        return (int) source.getElements().stream()
                .filter(element -> ElementType.IMAGE.name().equals(element.getType()))
                .count();
    }

    /** 非空白码点数 */
    private static int nonBlankCount(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (!Character.isWhitespace(codePoint)) {
                count++;
            }
            i += Character.charCount(codePoint);
        }
        return count;
    }
}
