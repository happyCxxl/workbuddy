package com.workbuddy.worker.structure.craft;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.parse.ParseUnitResult;
import com.workbuddy.common.domain.parse.UnitLine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * 页眉页脚带判定（文档级）：只读单元行摘要与版面高度，按「页顶/页底区域 + 跨单元重复」判定，
 * 输出每个单元里命中页眉/页脚的行。
 *
 * <p>判定只看文本与盒子，不产出元素、不改元素类型：类型标记由调用方按元素盒子是否整行落在带内决定，
 * 这样避免把跨栏正文按行切碎。无版面高度（如 Office 无物理页）的单元不做带判定。
 */
public final class HeaderFooterDetector {

    /** 页码行共享键（页码元素按页各产一条） */
    public static final String PAGE_NUMBER_KEY = "__page_number__";

    private HeaderFooterDetector() {
    }

    /**
     * 执行判定。
     *
     * @param units 全部单元（按文档序）
     * @param rules 判定阈值
     * @return 与入参一一对应的单元判定结果
     */
    public static List<UnitBands> detect(List<ParseUnitResult> units, BandRules rules) {
        int runningMinPages = Math.max(rules.runningTextMinPages(), 1);
        Set<String> headerKeys = repeated(pagesByBandText(units,
                (unit, line) -> inHeaderBand(unit, line, rules), Function.identity()), runningMinPages);
        Set<String> footerTexts = repeated(pagesByBandText(units,
                (unit, line) -> inFooterBand(unit, line, rules), HeaderFooterDetector::footerTextKey), runningMinPages);
        List<Double> pageNumberCenters = pageNumberCenters(units, footerTexts, rules);

        Map<Integer, List<UnitLine>> headersByUnit = new LinkedHashMap<>();
        Map<Integer, List<UnitLine>> footersByUnit = new LinkedHashMap<>();
        forEachBandLine(units, (unit, line) -> true, (unit, line, text) -> {
            if (inHeaderBand(unit, line, rules) && headerKeys.contains(text)) {
                headersByUnit.computeIfAbsent(unit.getUnitIndex(), k -> new ArrayList<>()).add(line);
            }
            if (inFooterBand(unit, line, rules)
                    && isFooterLine(unit, line, text, footerTexts, pageNumberCenters, rules)) {
                footersByUnit.computeIfAbsent(unit.getUnitIndex(), k -> new ArrayList<>()).add(line);
            }
        });

        List<UnitBands> result = new ArrayList<>(units.size());
        for (ParseUnitResult unit : units) {
            result.add(new UnitBands(unit.getUnitIndex(),
                    headersByUnit.getOrDefault(unit.getUnitIndex(), List.of()),
                    footersByUnit.getOrDefault(unit.getUnitIndex(), List.of())));
        }
        return result;
    }

    /**
     * 页眉文本键：判定为页眉的行文本去重集合。
     *
     * @param units 全部单元
     * @param rules 判定阈值
     * @return 页眉文本键
     */
    public static Set<String> detectHeaders(List<ParseUnitResult> units, BandRules rules) {
        Set<String> headers = new LinkedHashSet<>();
        for (UnitBands band : detect(units, rules)) {
            for (UnitLine line : band.headers()) {
                headers.add(normalizeKey(line.getText()));
            }
        }
        return headers;
    }

    /**
     * 页脚文本键与页码中心：判定为页脚的行按「页码 / 基础文本」分列。
     *
     * @param units 全部单元
     * @param rules 判定阈值
     * @return 页脚文本基础键与页码中心
     */
    public static FooterKeys detectFooters(List<ParseUnitResult> units, BandRules rules) {
        Set<String> texts = new LinkedHashSet<>();
        List<Double> centers = new ArrayList<>();
        for (UnitBands band : detect(units, rules)) {
            for (UnitLine line : band.footers()) {
                String text = normalizeKey(line.getText());
                if (isPageNumberLine(text)) {
                    centers.add(centerOf(line));
                } else {
                    texts.add(footerBase(text));
                }
            }
        }
        return new FooterKeys(texts, centers);
    }

    /** 带内非空文本行 → 出现过的单元序号集合；键为空表示该行不参与聚合（页底页码行） */
    private static Map<String, Set<Integer>> pagesByBandText(List<ParseUnitResult> units,
                                                            BiPredicate<ParseUnitResult, UnitLine> band,
                                                            Function<String, String> keyOf) {
        Map<String, Set<Integer>> pagesByText = new LinkedHashMap<>();
        forEachBandLine(units, band, (unit, line, text) -> {
            String key = keyOf.apply(text);
            if (key != null) {
                pagesByText.computeIfAbsent(key, k -> new HashSet<>()).add(unit.getUnitIndex());
            }
        });
        return pagesByText;
    }

    /** 遍历带内的非空文本行：统一跳过无几何单元与空白行 */
    private static void forEachBandLine(List<ParseUnitResult> units, BiPredicate<ParseUnitResult, UnitLine> band,
                                        BandLineConsumer action) {
        for (ParseUnitResult unit : units) {
            if (!hasGeometry(unit)) {
                continue;
            }
            for (UnitLine line : unit.getLines()) {
                String text = normalizeKey(line.getText());
                if (StrUtil.isNotBlank(text) && band.test(unit, line)) {
                    action.accept(unit, line, text);
                }
            }
        }
    }

    /** 页底非页码文本的基础键（页脚常与页码同行，先剥离行尾页码再聚合）；页码行不参与 */
    private static String footerTextKey(String text) {
        return isPageNumberLine(text) ? null : footerBase(text);
    }

    /** 出现单元数达阈值的文本键 */
    private static Set<String> repeated(Map<String, Set<Integer>> pagesByText, int minPages) {
        Set<String> keys = new LinkedHashSet<>();
        pagesByText.forEach((text, pages) -> {
            if (pages.size() >= minPages) {
                keys.add(text);
            }
        });
        return keys;
    }

    /**
     * 页码行判定：页底带内纯数字行，且与正上方正文行留出空白（内容空洞），跨单元横向中心成组、组内单元数达阈值。
     */
    private static List<Double> pageNumberCenters(List<ParseUnitResult> units, Set<String> footerTexts,
                                                  BandRules rules) {
        Map<Integer, UnitLine> lineByUnit = new LinkedHashMap<>();
        Map<Integer, Integer> countByUnit = new LinkedHashMap<>();
        forEachBandLine(units,
                (unit, line) -> inFooterBand(unit, line, rules)
                        && isPageNumberLine(normalizeKey(line.getText())),
                (unit, line, text) -> {
                    if (!isolated(unit, line, footerTexts, rules.pageNumberIsolationRatio())) {
                        return;
                    }
                    countByUnit.merge(unit.getUnitIndex(), 1, Integer::sum);
                    lineByUnit.putIfAbsent(unit.getUnitIndex(), line);
                });
        Map<Integer, UnitLine> singleByUnit = new LinkedHashMap<>();
        lineByUnit.forEach((unitIndex, line) -> {
            if (countByUnit.getOrDefault(unitIndex, 0) == 1) {
                singleByUnit.put(unitIndex, line);
            }
        });
        int minPages = Math.max(rules.pageNumberMinPages(), rules.runningTextMinPages());
        return groupByCenter(singleByUnit, minPages, rules.pageNumberXTolerance());
    }

    /** 同一横向中心（容差内）的单元数达阈值的中心值 */
    private static List<Double> groupByCenter(Map<Integer, UnitLine> lineByUnit, int minPages, double xTolerance) {
        List<Double> centers = new ArrayList<>();
        List<Map.Entry<Integer, UnitLine>> candidates = new ArrayList<>(lineByUnit.entrySet());
        boolean[] used = new boolean[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            if (used[i]) {
                continue;
            }
            double center = centerOf(candidates.get(i).getValue());
            Set<Integer> groupUnits = new HashSet<>();
            for (int j = i; j < candidates.size(); j++) {
                if (!used[j] && Math.abs(centerOf(candidates.get(j).getValue()) - center) <= xTolerance) {
                    used[j] = true;
                    groupUnits.add(candidates.get(j).getKey());
                }
            }
            if (groupUnits.size() >= minPages) {
                centers.add(center);
            }
        }
        return centers;
    }

    /** 行是否命中页脚：页脚文本键命中，或页码中心命中 */
    private static boolean isFooterLine(ParseUnitResult unit, UnitLine line, String text, Set<String> footerTexts,
                                        List<Double> pageNumberCenters, BandRules rules) {
        if (footerTexts.contains(footerBase(text))) {
            return true;
        }
        if (!isPageNumberLine(text)) {
            return false;
        }
        return isolated(unit, line, footerTexts, rules.pageNumberIsolationRatio())
                && centerMatched(line, pageNumberCenters, rules.pageNumberXTolerance());
    }

    /** 内容空洞：候选行与正上方最近正文行之间留出 ≥ 行高 × 比例 的空白；页脚文本行不计入正文 */
    private static boolean isolated(ParseUnitResult unit, UnitLine candidate, Set<String> footerTexts, double ratio) {
        double top = candidate.getY();
        double nearestBottom = -1;
        for (UnitLine line : unit.getLines()) {
            if (line.getY() + line.getHeight() > top || !overlapsHorizontally(line, candidate)) {
                continue;
            }
            if (footerTexts.contains(footerBase(normalizeKey(line.getText())))) {
                continue;
            }
            nearestBottom = Math.max(nearestBottom, line.getY() + line.getHeight());
        }
        return nearestBottom < 0 || top - nearestBottom >= candidate.getHeight() * ratio;
    }

    /** 两行横向是否交叠（「正上方」的判据） */
    private static boolean overlapsHorizontally(UnitLine line, UnitLine candidate) {
        return line.getX() < candidate.getX() + candidate.getWidth()
                && line.getX() + line.getWidth() > candidate.getX();
    }

    /** 横向中心是否落在已确认的页码中心容差内 */
    private static boolean centerMatched(UnitLine line, List<Double> centers, double xTolerance) {
        double center = centerOf(line);
        for (double candidate : centers) {
            if (Math.abs(candidate - center) <= xTolerance) {
                return true;
            }
        }
        return false;
    }

    /** 行横向中心 */
    private static double centerOf(UnitLine line) {
        return line.getX() + line.getWidth() / 2;
    }

    /** 单元是否有版面几何（无几何不做带判定） */
    private static boolean hasGeometry(ParseUnitResult unit) {
        return unit.getLayoutHeight() != null && unit.getLayoutHeight() > 0;
    }

    /** 行是否落在页顶带 */
    private static boolean inHeaderBand(ParseUnitResult unit, UnitLine line, BandRules rules) {
        return line.getY() < unit.getLayoutHeight() * rules.headerRatio();
    }

    /** 行是否落在页底带（行底越过页底带上边界） */
    private static boolean inFooterBand(ParseUnitResult unit, UnitLine line, BandRules rules) {
        return line.getY() + line.getHeight() >= unit.getLayoutHeight() * (1 - rules.footerRatio());
    }

    /**
     * 比较键空白归一化：去首尾空白、连续空白压成一个空格、去掉零宽字符。只用于文本比对，不改原文。
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

    /**
     * 页脚基础文本：纯页码行 → 共享键；否则剥离行尾页码（「XX项目 3」→「XX项目」），
     * 无行尾页码则原样（按完整文本聚合）。
     *
     * @param text 归一化后的页脚行文本
     * @return 基础文本键（纯页码行为共享键）
     */
    public static String footerBase(String text) {
        if (isPageNumberLine(text)) {
            return PAGE_NUMBER_KEY;
        }
        String stripped = text.replaceAll("\\s*(第\\s*[0-9一二三四五六七八九十百]+\\s*页|[-–—]?\\d{1,4}[-–—]?)$", "");
        return StrUtil.isBlank(stripped) ? text : stripped;
    }

    /** 页码形态：纯数字（可带 - 装饰）或 第X页 */
    private static boolean isPageNumberLine(String text) {
        return text.matches("^[-–—]?\\s*\\d{1,4}\\s*[-–—]?$")
                || text.matches("^第\\s*[0-9一二三四五六七八九十百]+\\s*页$");
    }

    /** 带内文本行回调（携带已归一化文本） */
    @FunctionalInterface
    private interface BandLineConsumer {

        /**
         * 处理一行。
         *
         * @param unit 所在单元
         * @param line 文本行
         * @param text 归一化后的文本
         */
        void accept(ParseUnitResult unit, UnitLine line, String text);
    }

    /**
     * 判定阈值。
     *
     * @param headerRatio                页顶带占版面高度比例
     * @param footerRatio                页底带占版面高度比例
     * @param runningTextMinPages        页眉/页脚文本至少出现的单元数
     * @param pageNumberMinPages         页码至少出现的单元数
     * @param pageNumberXTolerance       页码横向中心容差（pt）
     * @param pageNumberIsolationRatio   页码候选行与正上方正文行之间所需空白（行高倍数）
     */
    public record BandRules(double headerRatio, double footerRatio, int runningTextMinPages,
                            int pageNumberMinPages, double pageNumberXTolerance,
                            double pageNumberIsolationRatio) {
    }

    /**
     * 单元判定结果。
     *
     * @param unitIndex 单元序号
     * @param headers   命中页眉的行（按行序）
     * @param footers   命中页脚的行（按行序）
     */
    public record UnitBands(int unitIndex, List<UnitLine> headers, List<UnitLine> footers) {

        /**
         * 复制行列表，避免调用方改动判定结果。
         */
        public UnitBands {
            headers = List.copyOf(headers);
            footers = List.copyOf(footers);
        }
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
         * @param unit          所在单元
         * @param rules         判定阈值
         * @return 命中页脚为 true
         */
        public boolean matches(String footerBaseKey, UnitLine line, ParseUnitResult unit, BandRules rules) {
            if (texts.contains(footerBaseKey)) {
                return true;
            }
            if (!PAGE_NUMBER_KEY.equals(footerBaseKey)) {
                return false;
            }
            return isolated(unit, line, texts, rules.pageNumberIsolationRatio())
                    && centerMatched(line, pageNumberCenters, rules.pageNumberXTolerance());
        }
    }
}
