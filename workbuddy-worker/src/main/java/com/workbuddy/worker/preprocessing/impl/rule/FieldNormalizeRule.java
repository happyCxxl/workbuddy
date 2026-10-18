package com.workbuddy.worker.preprocessing.impl.rule;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.preprocess.NormalizedField;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.preprocess.PreprocessFieldType;
import com.workbuddy.common.enums.preprocess.PreprocessParam;
import com.workbuddy.common.enums.preprocess.PreprocessRule;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.preprocessing.rule.CleanRule;
import com.workbuddy.worker.preprocessing.rule.RuleContext;
import com.workbuddy.worker.preprocessing.rule.RuleOutcome;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ⑥ 招投标字段规范化（设计 11.4 规则表，自研行业行规）：
 * 执行顺序 = 冲突优先级（金额 > 日期 > 单位 > 证书号）；规则幂等（已规范化文本重复执行无副作用）；
 * 改写只落 normalizedText/normalizedFields，rawText 永不动；拿不准不改写 + MANUAL_REVIEW。
 *
 * <p>口径（11.7 示例为准）：中文大写金额只提取字段不改写文本（大写是合规原文）；
 * 千分位去逗号、中文日期/分隔符日期转 ISO、㎡ 族统一"平方米"会改写检索文本；
 * ￥/元/RMB 不做文本映射；编号层级字符串（1.11）与限定词（不低于）原样保留。
 */
@Component
public class FieldNormalizeRule implements CleanRule {

    /** 千分位金额（1,234,567.89） */
    private static final Pattern THOUSAND_SEP = Pattern.compile("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?");

    /** 中文大写金额（≥4 个财务大写字符） */
    private static final Pattern CN_AMOUNT = Pattern.compile("[零壹贰叁肆伍陆柒捌玖拾佰仟万亿圆元整角分毛]{4,}");

    /** 中文日期（二〇二六年八月二十五日；〇 经 NFKC 可能已转 0，年份含 ASCII 数字） */
    private static final Pattern CN_DATE = Pattern.compile(
            "([〇零一二三四五六七八九0-9]{2,4})年([一二三四五六七八九十]{1,3})月([一二三四五六七八九十]{1,3})日");

    /** 干支纪年（拿不准，人工复核） */
    private static final Pattern GANZHI_YEAR = Pattern.compile("[甲乙丙丁戊己庚辛壬癸][子丑寅卯辰巳午未申酉戌亥]年");

    /** 分隔符日期（2026/8/25、2026.8.25、2026-8-25） */
    private static final Pattern SEP_DATE = Pattern.compile("(\\d{4})[/.\\-](\\d{1,2})[/.\\-](\\d{1,2})");

    /** 数字 + 面积单位（10㎡ / 10m2 / 10平方米） */
    private static final Pattern AREA_WITH_NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(㎡|m2|M2|平方米)");

    /** 裸面积单位（无数字前缀） */
    private static final Pattern AREA_BARE = Pattern.compile("㎡|m2|M2");

    /** 证书号/注册号（原文不变，提取结构化字段） */
    private static final Pattern CERT_NO = Pattern.compile(
            "(证书编号|证书号|注册号)\\s*[:：]?\\s*(?:[Nn][Oo]\\.?\\s*)?([A-Za-z0-9][A-Za-z0-9\\-]{3,})");

    private static final Map<Character, Integer> CN_DIGITS = Map.ofEntries(
            Map.entry('零', 0), Map.entry('壹', 1), Map.entry('贰', 2), Map.entry('叁', 3),
            Map.entry('肆', 4), Map.entry('伍', 5), Map.entry('陆', 6), Map.entry('柒', 7),
            Map.entry('捌', 8), Map.entry('玖', 9));

    private static final Map<Character, Integer> CN_DATE_DIGITS = Map.ofEntries(
            Map.entry('〇', 0), Map.entry('零', 0), Map.entry('一', 1), Map.entry('二', 2),
            Map.entry('三', 3), Map.entry('四', 4), Map.entry('五', 5), Map.entry('六', 6),
            Map.entry('七', 7), Map.entry('八', 8), Map.entry('九', 9),
            Map.entry('0', 0), Map.entry('1', 1), Map.entry('2', 2), Map.entry('3', 3), Map.entry('4', 4),
            Map.entry('5', 5), Map.entry('6', 6), Map.entry('7', 7), Map.entry('8', 8), Map.entry('9', 9));

    @Override
    public String name() {
        return "field-normalize-v1";
    }

    @Override
    public String stepName() {
        return "字段规范化";
    }

    @Override
    public int order() {
        return 6;
    }

    @Override
    public boolean enabledIn(PreprocessStrategy strategy) {
        return strategy.enabled(PreprocessRule.FIELD, true);
    }

    @Override
    public RuleOutcome apply(ViewElement element, RuleContext context) {
        RuleOutcome outcome = new RuleOutcome();
        int[] changed = {0};
        PreprocessStrategy strategy = context.getStrategy();

        if (StrUtil.isNotBlank(element.getNormalizedText())) {
            element.setNormalizedText(normalize(element.getNormalizedText(),
                    outcome.getTraces(), outcome.getFields(), changed, strategy));
        }
        boolean cellMatched = false;
        if (NullUtil.isNotNull(element.getCells())) {
            for (ViewCell cell : element.getCells()) {
                if (StrUtil.isBlank(cell.getNormalizedText())) {
                    continue;
                }
                int traceBefore = cell.getTrace().size();
                cell.setNormalizedText(normalize(cell.getNormalizedText(),
                        cell.getTrace(), outcome.getFields(), changed, strategy));
                cellMatched |= cell.getTrace().size() > traceBefore;
            }
        }
        outcome.setMatched(!outcome.getTraces().isEmpty() || cellMatched);
        outcome.setChangedCount(changed[0]);
        return outcome;
    }

    /**
     * 按优先级顺序（金额&gt;日期&gt;单位&gt;证书号）规范化文本；轨迹与字段写入传入集合。
     * 各字段类型按策略子开关独立启用（关闭的类型跳过对应处理段）。
     */
    private String normalize(String text, List<TraceEntry> traces, List<NormalizedField> fields, int[] changed,
                             PreprocessStrategy strategy) {
        String work = text;
        work = normalizeAmount(work, traces, fields, changed, strategy);
        work = normalizeDate(work, traces, fields, changed, strategy);
        work = normalizeArea(work, traces, fields, changed, strategy);
        normalizeCertNo(work, traces, fields, strategy);
        return work;
    }

    /** 金额：千分位去逗号 + 中文大写金额提取 */
    private String normalizeAmount(String work, List<TraceEntry> traces, List<NormalizedField> fields,
                                   int[] changed, PreprocessStrategy strategy) {
        if (strategy.boolParam(PreprocessRule.FIELD, PreprocessParam.FIELD_AMOUNT, true)) {
            work = replaceAll(work, THOUSAND_SEP, match -> {
                String repl = match.group().replace(",", "");
                traces.add(TraceEntry.of("thousand-sep-v1", PreprocessFieldType.AMOUNT.name(),
                        TraceEntry.ACTION_REPLACE, match.group(), repl, "千分位逗号去除，小数位原样"));
                fields.add(NormalizedField.of(PreprocessFieldType.AMOUNT.name(), repl, "元", "thousand-sep-v1"));
                changed[0]++;
                return repl;
            });

            Matcher amountMatcher = CN_AMOUNT.matcher(work);
            while (amountMatcher.find()) {
                String span = amountMatcher.group();
                String value = parseCnAmount(span);
                if (NullUtil.isNull(value)) {
                    traces.add(TraceEntry.of("amount-cn-v1", PreprocessFieldType.AMOUNT.name(),
                            TraceEntry.ACTION_MANUAL_REVIEW, span, null, "复杂财务表达（拿不准），不改写"));
                } else {
                    traces.add(TraceEntry.of("amount-cn-v1", PreprocessFieldType.AMOUNT.name(),
                            TraceEntry.ACTION_REPLACE, span, value, "大写金额提取，原文保留"));
                    fields.add(NormalizedField.of(PreprocessFieldType.AMOUNT.name(), value, "元", "amount-cn-v1"));
                }
            }
        }
        return work;
    }

    /** 日期：干支纪年人工复核 + 中文日期/分隔符日期转 ISO */
    private String normalizeDate(String work, List<TraceEntry> traces, List<NormalizedField> fields,
                                 int[] changed, PreprocessStrategy strategy) {
        if (strategy.boolParam(PreprocessRule.FIELD, PreprocessParam.FIELD_DATE, true)) {
            Matcher ganzhiMatcher = GANZHI_YEAR.matcher(work);
            while (ganzhiMatcher.find()) {
                traces.add(TraceEntry.of("chinese-date-v1", PreprocessFieldType.DATE.name(),
                        TraceEntry.ACTION_MANUAL_REVIEW, ganzhiMatcher.group(), null, "干支纪年（拿不准），不改写"));
            }

            work = replaceAll(work, CN_DATE, match -> {
                String iso = cnDateToIso(match.group(1), match.group(2), match.group(3));
                if (NullUtil.isNull(iso)) {
                    traces.add(TraceEntry.of("chinese-date-v1", PreprocessFieldType.DATE.name(),
                            TraceEntry.ACTION_MANUAL_REVIEW, match.group(), null, "中文日期解析失败或日期不存在（拿不准），不改写"));
                    return match.group();
                }
                traces.add(TraceEntry.of("chinese-date-v1", PreprocessFieldType.DATE.name(),
                        TraceEntry.ACTION_REPLACE, match.group(), iso, "中文日期转 ISO"));
                fields.add(NormalizedField.of(PreprocessFieldType.DATE.name(), iso, null, "chinese-date-v1"));
                changed[0]++;
                return iso;
            });

            work = replaceAll(work, SEP_DATE, match -> {
                String iso = isoDateOf(Integer.parseInt(match.group(1)),
                        Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3)));
                if (NullUtil.isNull(iso)) {
                    traces.add(TraceEntry.of("date-sep-v1", PreprocessFieldType.DATE.name(),
                            TraceEntry.ACTION_MANUAL_REVIEW, match.group(), null, "分隔符日期越界或日期不存在（拿不准），不改写"));
                    return match.group();
                }
                if (iso.equals(match.group())) {
                    return match.group();
                }
                traces.add(TraceEntry.of("date-sep-v1", PreprocessFieldType.DATE.name(),
                        TraceEntry.ACTION_REPLACE, match.group(), iso, "分隔符日期统一 ISO 并补零"));
                fields.add(NormalizedField.of(PreprocessFieldType.DATE.name(), iso, null, "date-sep-v1"));
                changed[0]++;
                return iso;
            });
        }
        return work;
    }

    /** 面积单位统一（10㎡ → 10平方米，提取 AREA 字段） */
    private String normalizeArea(String work, List<TraceEntry> traces, List<NormalizedField> fields,
                                 int[] changed, PreprocessStrategy strategy) {
        if (strategy.boolParam(PreprocessRule.FIELD, PreprocessParam.FIELD_AREA, true)) {
            work = replaceAll(work, AREA_WITH_NUMBER, match -> {
                String repl = match.group(1) + "平方米";
                if (repl.equals(match.group())) {
                    return match.group();
                }
                traces.add(TraceEntry.of("unit-normalize-v1", PreprocessFieldType.AREA.name(),
                        TraceEntry.ACTION_REPLACE, match.group(), repl, "面积单位统一为平方米"));
                fields.add(NormalizedField.of(PreprocessFieldType.AREA.name(), match.group(1), "平方米",
                        "unit-normalize-v1"));
                changed[0]++;
                return repl;
            });
            work = replaceAll(work, AREA_BARE, match -> {
                traces.add(TraceEntry.of("unit-normalize-v1", PreprocessFieldType.AREA.name(),
                        TraceEntry.ACTION_REPLACE, match.group(), "平方米", "面积单位统一为平方米"));
                changed[0]++;
                return "平方米";
            });
        }
        return work;
    }

    /** 证书号/注册号 → 提取字段（原文不变） */
    private void normalizeCertNo(String work, List<TraceEntry> traces, List<NormalizedField> fields,
                                 PreprocessStrategy strategy) {
        if (strategy.boolParam(PreprocessRule.FIELD, PreprocessParam.FIELD_CERT_NO, true)) {
            Matcher certMatcher = CERT_NO.matcher(work);
            while (certMatcher.find()) {
                traces.add(TraceEntry.of("cert-extract-v1", PreprocessFieldType.CERT_NO.name(),
                        TraceEntry.ACTION_EXTRACT, matchPrefix(work, certMatcher), certMatcher.group(2),
                        "证书编号提取（原文不变）"));
                fields.add(NormalizedField.of(PreprocessFieldType.CERT_NO.name(), certMatcher.group(2), null,
                        "cert-extract-v1"));
            }
        }
    }

    private String matchPrefix(String text, Matcher matcher) {
        int end = matcher.end(2);
        return StrUtil.maxLength(text.substring(0, Math.min(end, text.length())), 40);
    }

    private String replaceAll(String text, Pattern pattern, Function<MatchResult, String> replacer) {
        return pattern.matcher(text).replaceAll(replacer);
    }

    /**
     * 中文大写金额 → 数字（两位小数固定，如 3005000.00）；
     * 拿不准（多个亿/两个以上万）返回 null。
     */
    private String parseCnAmount(String span) {
        long total = 0;
        long section = 0;
        long digit = 0;
        int yiCount = 0;
        int wanCount = 0;
        long tenths = 0;
        long hundredths = 0;
        for (char c : span.toCharArray()) {
            if (c == '元' || c == '圆' || c == '整') {
                continue;
            }
            if (c == '角' || c == '毛') {
                tenths = digit;
                digit = 0;
                continue;
            }
            if (c == '分') {
                hundredths = digit;
                digit = 0;
                continue;
            }
            Integer d = CN_DIGITS.get(c);
            if (NullUtil.isNotNull(d)) {
                digit = d;
                continue;
            }
            switch (c) {
                case '拾' -> section += (digit == 0 ? 1 : digit) * 10;
                case '佰' -> section += (digit == 0 ? 1 : digit) * 100;
                case '仟' -> section += (digit == 0 ? 1 : digit) * 1000;
                case '万' -> {
                    wanCount++;
                    section += digit;
                    total += section * 10000;
                    section = 0;
                }
                case '亿' -> {
                    yiCount++;
                    section += digit;
                    total = (total + section) * 100000000;
                    section = 0;
                }
                default -> {
                    return null;
                }
            }
            digit = 0;
        }
        total += section + digit;
        if (yiCount > 1 || wanCount > 2) {
            return null;
        }
        if (total == 0 && tenths == 0 && hundredths == 0) {
            return null;
        }
        return String.format("%d.%02d", total, tenths * 10 + hundredths);
    }

    /**
     * 中文日期 → ISO（yyyy-MM-dd）；年份非法、月日越界或日期不存在返回 null。
     */
    private String cnDateToIso(String yearCn, String monthCn, String dayCn) {
        int year = cnYear(yearCn);
        if (year <= 0) {
            return null;
        }
        return isoDateOf(year, cnSmallNumber(monthCn), cnSmallNumber(dayCn));
    }

    /**
     * 年/月/日 → ISO 日期（yyyy-MM-dd）；月日越界或日期不存在（如 2 月 30 日）返回 null。
     * 日历合法性交给 {@link LocalDate} 判定，避免写出不存在的日期。
     */
    private String isoDateOf(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day).toString();
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** 年份逐字映射（〇零一二三四五六七八九 + ASCII 数字） */
    private int cnYear(String s) {
        int value = 0;
        for (char c : s.toCharArray()) {
            Integer d = CN_DATE_DIGITS.get(c);
            if (NullUtil.isNull(d)) {
                return -1;
            }
            value = value * 10 + d;
        }
        return value;
    }

    /** 月/日"十"进制数：二十五→25、十→10、五→5 */
    private int cnSmallNumber(String s) {
        if (!s.contains("十")) {
            Integer d = CN_DATE_DIGITS.get(s.charAt(0));
            return NullUtil.isNull(d) ? -1 : d;
        }
        String[] parts = s.split("十", -1);
        int tens = parts[0].isEmpty() ? 1 : cnYear(parts[0]);
        int ones = parts[1].isEmpty() ? 0 : cnYear(parts[1]);
        return tens * 10 + ones;
    }
}
