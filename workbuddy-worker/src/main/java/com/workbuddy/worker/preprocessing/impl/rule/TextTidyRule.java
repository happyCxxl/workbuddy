package com.workbuddy.worker.preprocessing.impl.rule;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.preprocess.TraceEntry;
import com.workbuddy.common.domain.preprocess.ViewCell;
import com.workbuddy.common.domain.preprocess.ViewElement;
import com.workbuddy.common.enums.preprocess.PreprocessParam;
import com.workbuddy.common.enums.preprocess.PreprocessRule;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.preprocessing.PreprocessProperties;
import com.workbuddy.worker.preprocessing.impl.TextBase;
import com.workbuddy.worker.preprocessing.rule.CleanRule;
import com.workbuddy.worker.preprocessing.rule.RuleContext;
import com.workbuddy.worker.preprocessing.rule.RuleOutcome;
import com.workbuddy.worker.preprocessing.strategy.PreprocessStrategy;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * ② 段落/列表/表格文本整理（单设规则，拆子规则集）：文本级整理，不动结构
 * （不合并单元格/不改层级/不改行列）。子规则：空白归一化（行内空白折叠+trim+单元格空白）、
 * 折行段落合并（同段内硬折行合成一行，段间与列表项不动）、
 * 标点与引号统一（重复标点折叠+弯引号转直引号）、断词连字符合并（软连字符剔除+英文断行）、
 * 项目符号后补空格、移除 URL/邮箱。无变化：表格记 KEEP，段落不记轨迹。
 *
 * @author cxxl
 */
@Component
public class TextTidyRule implements CleanRule {

    /** 弯引号（NFKC 不转换，单独统一为直引号） */
    private static final Pattern CURLY_QUOTES = Pattern.compile("[“”‘’]");

    /** URL（http/https/www 前缀，非空白字符到空白边界） */
    private static final Pattern URL_PATTERN = Pattern.compile("(?:https?://|www\\.)\\S+");

    /** 邮箱地址 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** 续行判据：下一行以项目符号或编号开头时不合并（避免把列表项粘成一段） */
    private static final Pattern LINE_START_MARKER =
            Pattern.compile("^(?:[•·▪◦\\-–—*]|\\d{1,2}[.、)）]|（\\d{1,2}）|\\(\\d{1,2}\\))");

    /** CJK 及全角区字符（折行处两侧均为非 CJK 时补一个空格） */
    private static final Pattern CJK_CHAR =
            Pattern.compile("[\\u3000-\\u303F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\uFF00-\\uFFEF]");

    @Override
    public String name() {
        return "text-tidy-v1";
    }

    @Override
    public String stepName() {
        return "段落/表格文本整理";
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public boolean enabledIn(PreprocessStrategy strategy) {
        return strategy.enabled(PreprocessRule.TIDY, true);
    }

    @Override
    public RuleOutcome apply(ViewElement element, RuleContext context) {
        PreprocessProperties properties = context.getProperties();
        PreprocessStrategy strategy = context.getStrategy();
        boolean changed = false;
        String before = element.getDisplayText();
        String display = element.getDisplayText();
        if (StrUtil.isNotBlank(display)) {
            String tidied = tidy(display, strategy);
            if (!tidied.equals(display)) {
                display = tidied;
                element.setDisplayText(tidied);
                element.setNormalizedText(TextBase.normalizeBase(tidied));
                changed = true;
            }
        }
        boolean hasCells = NullUtil.isNotNull(element.getCells()) && !element.getCells().isEmpty();
        if (hasCells) {
            for (ViewCell cell : element.getCells()) {
                if (StrUtil.isNotBlank(cell.getNormalizedText())) {
                    String tidied = tidy(cell.getNormalizedText(), strategy);
                    if (!tidied.equals(cell.getNormalizedText())) {
                        cell.setNormalizedText(tidied);
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            TraceEntry trace = TraceEntry.of(name(), null, TraceEntry.ACTION_REPLACE,
                    StrUtil.maxLength(before, properties.getTraceBeforeAfterMaxLen()),
                    StrUtil.maxLength(display, properties.getTraceBeforeAfterMaxLen()),
                    "文本级整理（不动结构）：空白归一化/折行段落合并/标点与引号统一/断词连字符/项目符号间隔/URL 邮箱移除（按策略子规则）");
            return RuleOutcome.hit(trace, 1);
        }
        if (hasCells) {
            TraceEntry trace = TraceEntry.of(name(), null, TraceEntry.ACTION_KEEP,
                    null, null, "单元格文本无需改写");
            return RuleOutcome.hit(trace, 0);
        }
        return RuleOutcome.none();
    }

    private String tidy(String text, PreprocessStrategy strategy) {
        String s = text;
        // 空白归一化：行内空白折叠（多个空白→单个）+ trim
        if (strategy.boolParam(PreprocessRule.TIDY, PreprocessParam.TIDY_WHITESPACE, true)) {
            s = s.replaceAll("[ \\t\\u00A0]+", " ").trim();
        }
        // 折行段落合并：同段内硬折行合成一行（段间空行与列表项行首不动）
        if (strategy.boolParam(PreprocessRule.TIDY, PreprocessParam.TIDY_JOIN_LINES, true)) {
            s = joinBrokenLines(s);
        }
        // 标点与引号统一：弯引号→直引号 + 重复标点折叠
        if (strategy.boolParam(PreprocessRule.TIDY, PreprocessParam.TIDY_PUNCT, true)) {
            s = CURLY_QUOTES.matcher(s).replaceAll(match -> switch (match.group()) {
                case "“", "”" -> "\"";
                default -> "'";
            });
            s = s.replaceAll("([。！？，；：、])\\1+", "$1");
        }
        // 断词连字符合并：软连字符剔除 + 英文断行连字符（仅连字符前为 ASCII 字母且后接小写字母）
        if (strategy.boolParam(PreprocessRule.TIDY, PreprocessParam.TIDY_DASHES, true)) {
            s = s.replace("\u00AD", "");
            s = s.replaceAll("([A-Za-z])- ([a-z])", "$1-$2");
        }
        // 项目符号后补空格（•要点 → • 要点）
        if (strategy.boolParam(PreprocessRule.TIDY, PreprocessParam.TIDY_BULLETS, true)) {
            s = s.replaceAll("([•·])(\\S)", "$1 $2");
        }
        // 移除 URL/邮箱
        if (strategy.boolParam(PreprocessRule.TIDY, PreprocessParam.TIDY_URLS, true)) {
            s = URL_PATTERN.matcher(s).replaceAll("");
            s = EMAIL_PATTERN.matcher(s).replaceAll("");
            s = s.replaceAll("[ \\t\\u00A0]{2,}", " ").trim();
        }
        return s;
    }

    /**
     * 折行段落合并：先按空行切成段落块（段间边界保留为单个换行），
     * 块内硬折行合并为一行——折行两侧都不是 CJK 时补一个空格；以项目符号/编号开头的行不合并。
     */
    private String joinBrokenLines(String text) {
        String[] blocks = text.split("\n\\s*\n");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < blocks.length; i++) {
            if (i > 0) {
                result.append('\n');
            }
            result.append(joinBlockLines(blocks[i]));
        }
        return result.toString();
    }

    private String joinBlockLines(String block) {
        String[] lines = block.split("\n");
        StringBuilder joined = new StringBuilder(lines[0].trim());
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || LINE_START_MARKER.matcher(line).find() || joined.isEmpty()) {
                joined.append('\n').append(line);
                continue;
            }
            boolean needsSpace = !CJK_CHAR.matcher(String.valueOf(joined.charAt(joined.length() - 1))).matches()
                    && !CJK_CHAR.matcher(String.valueOf(line.charAt(0))).matches();
            joined.append(needsSpace ? " " : "").append(line);
        }
        return joined.toString();
    }
}
