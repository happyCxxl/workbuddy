package com.workbuddy.worker.parser.pdf.assemble;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.signal.ParseFact;
import com.workbuddy.common.enums.parse.SignalType;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;

import java.util.List;

/**
 * 表格两条路径（线框 / 文本）共用的门限与降级留痕：
 * 假表门限判定，以及规则失败时「出 TABLE 事实 + 区域降级为段落」的统一口径。
 */
final class TableSupport {

    private TableSupport() {
    }

    /** 假表门限：非空单元格占比低于阈值即判为空白网格（弃表） */
    static boolean blankTable(List<List<String>> rowTexts, ParseProperties properties) {
        int total = 0;
        int filled = 0;
        for (List<String> row : rowTexts) {
            for (String text : row) {
                total++;
                if (StrUtil.isNotBlank(text)) {
                    filled++;
                }
            }
        }
        return total > 0 && (double) filled / total < properties.getTableMinFilledRatio();
    }

    /**
     * 表单门限：行列都不超过 3 且任一单元格是「标签：」形态时，判为表单/键值区而非数据表。
     * 这类区域有边框线但语义是字段标签，按表格产出会把普通正文塞进单元格。
     *
     * @param rowTexts 行文本
     * @return 疑似表单为 true
     */
    static boolean formLike(List<List<String>> rowTexts) {
        if (rowTexts.size() > 3) {
            return false;
        }
        int cols = rowTexts.stream().mapToInt(List::size).max().orElse(0);
        if (cols > 3) {
            return false;
        }
        for (List<String> row : rowTexts) {
            for (String text : row) {
                if (isLabelCell(text)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 短标签单元格：冒号结尾或长度不超过 12 的「标签：」形态 */
    private static boolean isLabelCell(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        String value = text.trim();
        boolean colon = value.contains("：") || value.contains(":");
        return colon && (value.length() <= 12 || value.endsWith("：") || value.endsWith(":"));
    }

    /** 表格规则失败：出 TABLE 事实并把区域降级为段落 */
    static void degrade(List<PageLine> lines, PageContent page, List<ParseElement> elements,
                        ParseSource source, String fileId, String evidence) {
        ParseFact fact = new ParseFact();
        fact.setType(SignalType.TABLE);
        fact.setRegion("page " + page.pageNo());
        fact.setEvidence(evidence);
        source.getFacts().add(fact);
        PdfBodyAssembler.addParagraph(lines, elements, fileId, page);
    }
}
