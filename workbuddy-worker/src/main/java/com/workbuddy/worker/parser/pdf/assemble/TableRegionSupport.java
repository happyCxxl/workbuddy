package com.workbuddy.worker.parser.pdf.assemble;

import com.workbuddy.common.domain.parse.BBox;
import com.workbuddy.common.domain.parse.capability.TableRegion;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.pdf.extract.LineTexts;
import com.workbuddy.worker.parser.pdf.extract.PdfGeometry;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;

import java.util.List;

/**
 * 失败表 → 能力请求区域：把规则路径判不出结构的行块装成 {@link TableRegion}
 * （页码 + 视口坐标包围盒 + 块内文本），坐标系与解析产物一致。
 *
 * @author cxxl
 */
public final class TableRegionSupport {

    private TableRegionSupport() {
    }

    /**
     * 行块转请求区域。
     *
     * @param lines            块内文本行（按页内阅读顺序）
     * @param page             所在页
     * @param lineTextOptions  行文本重建口径（与产物同一套）
     * @return 请求区域；行块为空时返回 null
     */
    public static TableRegion of(List<PageLine> lines, PageContent page, LineTexts.Options lineTextOptions) {
        if (lines.isEmpty()) {
            return null;
        }
        BBox bounds = PdfGeometry.boundsOf(lines, PageLine::x, PageLine::y, PageLine::width, PageLine::height);
        TableRegion region = new TableRegion();
        region.setImageRef("page" + page.pageNo() + "-table-region");
        region.setPage(page.pageNo());
        region.setBbox(page.geometry().toViewport(bounds));
        region.setOcrText(blockText(lines, lineTextOptions));
        return region;
    }

    /** 块内文本：行文本按行间规则拼接（与段落元素同一口径） */
    private static String blockText(List<PageLine> lines, LineTexts.Options lineTextOptions) {
        StringBuilder text = new StringBuilder();
        String previous = null;
        for (PageLine line : lines) {
            String lineText = LineTexts.lineText(line.chars(), lineTextOptions);
            if (NullUtil.isNotNull(previous)) {
                text.append(LineTexts.lineSeparator(previous, lineText));
            }
            text.append(lineText);
            previous = lineText;
        }
        return text.toString();
    }
}
