package com.workbuddy.worker.parser.pdf.detect;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;

import com.workbuddy.worker.parser.pdf.model.PageLines;
import com.workbuddy.worker.parser.pdf.model.TextLine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PDF 页眉页脚识别（PdfBoxDocumentParser 专用助手）：
 * 页顶/页底区域内按文本聚合，出现页数达阈值即判页眉/页脚（同一页重复出现只计一页）；
 * 页脚剥离行尾页码后按"基础文本"聚合；页码按「形态 + 页底带 + 跨页同址 + 内容空洞度」判定，
 * 同页多条纯数字行不判，紧贴正文的页底数字行按正文处理。
 *
 * <p>输入是行级轻量摘要 {@link PageLines}（大文件分窗时全档只留这一层），判定只看文本与盒子。
 */
public final class HeaderFooterDetector {

    /** 页码行共享键（页码元素按页各产一条） */
    public static final String PAGE_NUMBER_KEY = "__page_number__";

    /**
     * 比较键空白归一化：去首尾空白、连续空白压成一个空格、去掉零宽字符。
     * 只用于文本比对，不改元素里的文本。
     *
     * @param text 原始文本
     * @return 归一化后的比较键
     */
    public static String normalizeKey(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        return text.replaceAll("[\\u200B-\\u200D\\uFEFF]", "")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private HeaderFooterDetector() {
    }

    /**
     * 页眉识别：页顶 headerAreaRatio 区域内按文本聚合，出现页数达阈值即判页眉。
     *
     * @param pages   全部页的行
     * @param context 解析上下文（阈值来源）
     * @return 页眉文本集合
     */
    public static Set<String> detectHeaders(List<PageLines> pages, ParseContext context) {
        double headerBand = context.getProperties().getHeaderAreaRatio();
        Map<String, Set<Integer>> pagesByText = new LinkedHashMap<>();
        for (PageLines page : pages) {
            double bandY = page.pageHeight() * headerBand;
            for (TextLine line : page.lines()) {
                String text = normalizeKey(line.text());
                if (line.y() < bandY && StrUtil.isNotBlank(text)) {
                    pagesByText.computeIfAbsent(text, k -> new HashSet<>()).add(page.pageNo());
                }
            }
        }
        int minPages = context.getProperties().getRunningTextMinPages();
        Set<String> headers = new LinkedHashSet<>();
        pagesByText.forEach((text, pageNos) -> {
            if (pageNos.size() >= minPages) {
                headers.add(text);
            }
        });
        return headers;
    }

    /**
     * 页脚识别：页底 footerAreaRatio 区域内分两步——
     * ① 页脚文本：非页码行按"基础文本"聚合（页脚常与页码同行，"XX项目 3" 剥离行尾页码后再判多页重复）；
     * ② 页码：纯数字行按「跨页中心在容差内成组、组内页数达阈值」确认，且候选行需过内容空洞度门限
     * （与正上方的正文行留出空白），紧贴正文的页底数字行不进页码候选。
     *
     * @param pages   全部页的行
     * @param context 解析上下文（阈值来源）
     * @return 页脚文本基础键与页码中心
     */
    public static FooterKeys detectFooters(List<PageLines> pages, ParseContext context) {
        ParseProperties properties = context.getProperties();
        Map<String, Set<Integer>> pagesByText = new LinkedHashMap<>();
        for (PageLines page : pages) {
            for (TextLine line : footerBandLines(page, properties.getFooterAreaRatio())) {
                String text = normalizeKey(line.text());
                if (isPageNumberLine(text)) {
                    continue;
                }
                pagesByText.computeIfAbsent(footerBase(text), k -> new HashSet<>()).add(page.pageNo());
            }
        }
        int minPages = properties.getRunningTextMinPages();
        Set<String> texts = new LinkedHashSet<>();
        pagesByText.forEach((text, pageNos) -> {
            if (pageNos.size() >= minPages) {
                texts.add(text);
            }
        });

        Map<Integer, TextLine> lineByPage = new LinkedHashMap<>();
        Map<Integer, Integer> countByPage = new LinkedHashMap<>();
        for (PageLines page : pages) {
            for (TextLine line : footerBandLines(page, properties.getFooterAreaRatio())) {
                if (!isPageNumberLine(normalizeKey(line.text()))
                        || !isolated(page, line, texts, properties.getPageNumberIsolationRatio())) {
                    continue;
                }
                countByPage.merge(page.pageNo(), 1, Integer::sum);
                lineByPage.putIfAbsent(page.pageNo(), line);
            }
        }
        return new FooterKeys(texts, pageNumberCenters(lineByPage, countByPage, properties));
    }

    /** 页底带内的行（版面坐标，左上原点；空白行不算） */
    private static List<TextLine> footerBandLines(PageLines page, double footerBand) {
        double bandY = page.pageHeight() * (1 - footerBand);
        return page.lines().stream()
                .filter(line -> line.y() + line.height() >= bandY && StrUtil.isNotBlank(line.text()))
                .toList();
    }

    /**
     * 内容空洞度：候选行与正上方最近的正文行之间留有 ≥ 行高 × 比例 的空白。
     * 页脚文本行（跨页重复的基础文本）属页脚家具，不计入正上方正文。
     */
    private static boolean isolated(PageLines page, TextLine candidate, Set<String> footerTexts, double ratio) {
        double top = candidate.y();
        double nearestBottom = -1;
        for (TextLine line : page.lines()) {
            if (line.y() + line.height() > top || !overlapsHorizontally(line, candidate)) {
                continue;
            }
            if (footerTexts.contains(footerBase(normalizeKey(line.text())))) {
                continue;
            }
            nearestBottom = Math.max(nearestBottom, line.y() + line.height());
        }
        if (nearestBottom < 0) {
            return true;
        }
        return top - nearestBottom >= candidate.height() * ratio;
    }

    /** 两行横向是否交叠（"正上方"的判据） */
    private static boolean overlapsHorizontally(TextLine line, TextLine candidate) {
        return line.x() < candidate.x() + candidate.width() && line.x() + line.width() > candidate.x();
    }

    /** 页码模式确认：同页至多一条，跨页中心容差内成组，组内页数达阈值即记该中心；多条数字页只排除本页 */
    private static List<Double> pageNumberCenters(Map<Integer, TextLine> lineByPage,
                                                  Map<Integer, Integer> countByPage,
                                                  ParseProperties properties) {
        List<Double> centers = new ArrayList<>();
        Map<Integer, TextLine> singleByPage = new LinkedHashMap<>();
        lineByPage.forEach((page, line) -> {
            if (countByPage.getOrDefault(page, 0) == 1) {
                singleByPage.put(page, line);
            }
        });
        int minPages = Math.max(properties.getPageNumberMinPages(), properties.getRunningTextMinPages());
        double xTolerance = properties.getPageNumberXTolerance();
        List<Map.Entry<Integer, TextLine>> candidates = new ArrayList<>(singleByPage.entrySet());
        boolean[] used = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            if (used[i]) {
                continue;
            }
            double center = centerOf(candidates.get(i).getValue());
            Set<Integer> groupPages = new HashSet<>();
            for (int j = i; j < candidates.size(); j++) {
                if (!used[j] && Math.abs(centerOf(candidates.get(j).getValue()) - center) <= xTolerance) {
                    used[j] = true;
                    groupPages.add(candidates.get(j).getKey());
                }
            }
            if (groupPages.size() >= minPages) {
                centers.add(center);
            }
        }
        return centers;
    }

    /** 行横向中心（页码同址判定口径） */
    private static double centerOf(TextLine line) {
        return line.x() + line.width() / 2;
    }

    /**
     * 页脚基础文本：纯页码行 → 共享键；否则剥离行尾页码（"XX项目 3"→"XX项目"），
     * 无行尾页码则原样（按完整文本聚合）。
     *
     * @param text 页脚行文本
     * @return 基础文本键（纯页码行为共享键）
     */
    public static String footerBase(String text) {
        if (isPageNumberLine(text)) {
            return PAGE_NUMBER_KEY;
        }
        String stripped = text.replaceAll("\\s*(第\\s*[0-9一二三四五六七八九十百]+\\s*页|[-–—]?\\d{1,4}[-–—]?)$", "");
        return StrUtil.isBlank(stripped) ? text : stripped;
    }

    /** 页码模式：纯数字（可带 - 装饰）或 第X页 */
    private static boolean isPageNumberLine(String text) {
        return text.matches("^[-–—]?\\s*\\d{1,4}\\s*[-–—]?$")
                || text.matches("^第\\s*[0-9一二三四五六七八九十百]+\\s*页$");
    }

    /**
     * 页脚判定结果。
     *
     * @param texts              重复出现的页脚文本基础键
     * @param pageNumberCenters  已确认的页码横向中心
     */
    public record FooterKeys(Set<String> texts, List<Double> pageNumberCenters) {

        /**
         * 行是否命中页脚：基础文本键命中，或页码中心命中且该行内容空洞。
         *
         * @param footerBaseKey 行的页脚基础文本键
         * @param line          文本行
         * @param page          所在页
         * @param properties    解析阈值配置
         * @return 命中页脚为 true
         */
        public boolean matches(String footerBaseKey, TextLine line, PageLines page, ParseProperties properties) {
            if (texts.contains(footerBaseKey)) {
                return true;
            }
            if (!PAGE_NUMBER_KEY.equals(footerBaseKey)) {
                return false;
            }
            return isolated(page, line, texts, properties.getPageNumberIsolationRatio())
                    && centerMatched(line, properties.getPageNumberXTolerance());
        }

        /** 横向中心是否落在已确认的页码中心容差内 */
        private boolean centerMatched(TextLine line, double xTolerance) {
            double center = centerOf(line);
            for (double candidate : pageNumberCenters) {
                if (Math.abs(candidate - center) <= xTolerance) {
                    return true;
                }
            }
            return false;
        }
    }
}
