package com.workbuddy.worker.parser.office.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.parse.FontInfo;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.Provenance;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.input.FileFormat;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.office.AbstractPoiDocumentParser;
import com.workbuddy.worker.parser.support.TocLineFeature;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeaderFooter;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DOCX 原生结构解析器（POI 路径）。
 * 产出 native 路：段落（含样式与字体事实，无样式标题不猜层级）、表格（行列/合并/isHeader 候选）、
 * 图片引用（仅引用+needsOcr）、页眉页脚部件、Office 结构路径溯源。
 */
@Slf4j
@Component
public class DocxDocumentParser extends AbstractPoiDocumentParser {

    /**
     * 是否支持该媒体类型。
     *
     * @param mimeType 媒体类型
     * @return 支持 DOCX 为 true
     */
    @Override
    public boolean supports(String mimeType) {
        return FileFormat.DOCX.getMimeType().equals(mimeType);
    }

    /**
     * DOCX 主流程：按 body 顺序产出段落、表格元素、段落内嵌图片引用与页眉页脚部件。
     *
     * @param source  待填充的原生解析结果
     * @param data    文件字节（已读全）
     * @param context 解析上下文
     */
    @Override
    protected void parseNative(ParseSource source, byte[] data, ParseContext context) {
        String fileId = context.getFileRef().getFileId();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
            List<IBodyElement> bodyElements = doc.getBodyElements();
            int paragraphIndex = 0;
            int tableIndex = 0;
            for (IBodyElement bodyElement : bodyElements) {
                if (bodyElement instanceof XWPFParagraph paragraph) {
                    ParseElement element = toDocxParagraphElement(paragraph, paragraphIndex, fileId);
                    if (NullUtil.isNotNull(element)) {
                        source.getElements().add(element);
                    }
                    // 段落内嵌图片：仅记引用 + needsOcr（OCR 预留）
                    appendDocxPictures(source, paragraph, paragraphIndex, fileId);
                    paragraphIndex++;
                } else if (bodyElement instanceof XWPFTable table) {
                    source.getElements().add(toDocxTableElement(table, tableIndex, fileId));
                    tableIndex++;
                }
            }
            // 页眉页脚部件（DOCX header/footer parts）：独立产出 HEADER/FOOTER 元素，处置归预处理环节
            appendDocxHeaderFooter(source, doc, fileId);
            fillVirtualPageMetrics(source, context.getProperties().getOfficePageChars());
        } catch (Exception e) {
            log.warn("DOCX 解析失败, fileId={}", fileId, e);
            throw new IllegalStateException("DOCX 解析失败: " + e.getMessage(), e);
        }
    }

    /** DOCX 页眉页脚部件 → HEADER/FOOTER 元素；单部件异常不阻断整档解析 */
    private void appendDocxHeaderFooter(ParseSource source, XWPFDocument doc, String fileId) {
        appendParts(source, doc.getHeaderList(), PartKind.HEADER, fileId);
        appendParts(source, doc.getFooterList(), PartKind.FOOTER, fileId);
    }

    /** 单个部件列表 → 元素（部件文本非空才产出；按部件出现顺序编号） */
    private void appendParts(ParseSource source, List<? extends XWPFHeaderFooter> parts, PartKind kind,
                             String fileId) {
        int index = 0;
        try {
            for (XWPFHeaderFooter part : parts) {
                String text = part.getParagraphs().stream()
                        .map(XWPFParagraph::getText)
                        .filter(StrUtil::isNotBlank)
                        .reduce("", String::concat);
                if (StrUtil.isNotBlank(text)) {
                    ParseElement element = ParseElement.of(kind.idPrefix() + index, kind.elementType());
                    element.setText(text);
                    element.setProvenance(new Provenance(fileId, kind.provenancePrefix() + index + "]"));
                    source.getElements().add(element);
                }
                index++;
            }
        } catch (Exception e) {
            log.warn("{}, fileId={}", kind.errorLog(), fileId, e);
        }
    }

    /** 页眉/页脚部件口径：id 前缀 / 元素类型 / 溯源前缀 / 读取失败告警文案 */
    private record PartKind(String idPrefix, ElementType elementType, String provenancePrefix, String errorLog) {

        private static final PartKind HEADER =
                new PartKind("h", ElementType.HEADER, "docx#header[", "DOCX 页眉部件读取失败");

        private static final PartKind FOOTER =
                new PartKind("f", ElementType.FOOTER, "docx#footer[", "DOCX 页脚部件读取失败");
    }

    /**
     * 段落文本与文本框文字合并：文本框内容在部分文件里已被 `getText()` 带出，已包含时不再追加
     * （否则同一段文字会出现两次）；段落为空时以文本框文字为准。
     *
     * @param text    段落自身文本（可能为空）
     * @param boxText 文本框文字（可能为空）
     * @return 合并后的段落文本
     */
    static String mergeTextBoxText(String text, String boxText) {
        String paragraphText = StrUtil.blankToDefault(text, "");
        if (StrUtil.isBlank(boxText) || paragraphText.contains(boxText)) {
            return paragraphText;
        }
        return paragraphText.isEmpty() ? boxText : paragraphText + "\n" + boxText;
    }

    /**
     * 文本框文字：段落 XML 里 `w:txbxContent` 内的 `w:t` 文本按出现顺序拼接。
     * POI 的 `XWPFParagraph.getText()` 只遍历 run 的 `w:t`，文本框内容（`w:txbxContent` / `w:ps:txbx`）不在其中。
     *
     * @param paragraphXml 段落 XML（`CTP.xmlText()`）
     * @return 文本框文字（无文本框给空串）
     */
    static String docxTextBoxText(String paragraphXml) {
        if (StrUtil.isBlank(paragraphXml)) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        Matcher contents = Pattern.compile("<w:txbxContent[^>]*>(.*?)</w:txbxContent>", Pattern.DOTALL)
                .matcher(paragraphXml);
        while (contents.find()) {
            Matcher runs = Pattern.compile("<w:t[^>]*>(.*?)</w:t>", Pattern.DOTALL).matcher(contents.group(1));
            while (runs.find()) {
                text.append(runs.group(1));
            }
        }
        return text.toString().trim();
    }

    /** DOCX 段落元素：空段跳过；样式名 + 首个有效 run 的字体事实（无样式标题只输出事实，层级归组装环节）。 */
    private ParseElement toDocxParagraphElement(XWPFParagraph paragraph, int index, String fileId) {
        String text = mergeTextBoxText(paragraph.getText(), docxTextBoxText(paragraph.getCTP().xmlText()));
        if (StrUtil.isBlank(text)) {
            return null;
        }
        ParseElement element = ParseElement.of("p" + index, ElementType.PARAGRAPH);
        element.setText(text);
        element.setStyle(paragraph.getStyleID());
        element.setTocCandidate(TocLineFeature.isTocLine(text));
        // 字体事实：取首个有数据的 run（无样式标题也只输出事实，层级归组装环节）
        for (XWPFRun run : paragraph.getRuns()) {
            if (NullUtil.isNull(run)) {
                continue;
            }
            FontInfo font = new FontInfo();
            font.setName(run.getFontFamily());
            Double size = run.getFontSizeAsDouble();
            font.setSize(NullUtil.isNotNull(size) && size > 0 ? size : null);
            font.setBold(NullUtil.isNotNull(run.isBold()) ? run.isBold() : null);
            // 无字体事实的 run（纯格式/换行/书签 run）不作为代表字体，继续找下一个：
            // 未加粗的 run 给的是 false 而不是 null，故只有 true 才算一条字体事实
            boolean hasFontFacts = NullUtil.isNotNull(font.getName()) || NullUtil.isNotNull(font.getSize())
                    || Boolean.TRUE.equals(font.getBold());
            if (!hasFontFacts) {
                continue;
            }
            element.setFont(font);
            break;
        }
        element.setProvenance(new Provenance(fileId, "office#document.xml/paragraph[" + index + "]"));
        return element;
    }

    /** 段落内嵌图片：仅记引用 + needsOcr；OCR_IMAGE 事实按段落聚合（一段多图只产一条，证据带张数）。 */
    private void appendDocxPictures(ParseSource source, XWPFParagraph paragraph, int index, String fileId) {
        int pictureIndex = 0;
        for (XWPFRun run : paragraph.getRuns()) {
            if (NullUtil.isNull(run) || run.getEmbeddedPictures().isEmpty()) {
                continue;
            }
            for (XWPFPicture picture : run.getEmbeddedPictures()) {
                String assetRef = picture.getPictureData().getFileName();
                ParseElement image = ParseElement.of("img" + index + "_" + pictureIndex, ElementType.IMAGE);
                image.setAssetRef(assetRef);
                image.setNeedsOcr(true);
                image.setProvenance(new Provenance(fileId,
                        "office#document.xml/paragraph[" + index + "]/pic[" + pictureIndex + "]"));
                source.getElements().add(image);
                pictureIndex++;
            }
        }
        if (pictureIndex > 0) {
            source.getFacts().add(fact("paragraph[" + index + "]",
                    "嵌入图片仅引用无文字（drawing 节点 " + pictureIndex + " 张）"));
        }
    }

    /**
     * DOCX 表格元素：列号按 gridSpan 推进（横向合并格只对应一个 tc，其后仍是真实单元格）；
     * vMerge 列内 continue 计数，结束回填 restart 单元格行跨度。
     */
    private ParseElement toDocxTableElement(XWPFTable table, int tableIndex, String fileId) {
        ParseElement tableElement = ParseElement.of("t" + tableIndex, ElementType.TABLE);
        List<XWPFTableRow> rows = table.getRows();
        tableElement.setRows(rows.size());
        tableElement.setCols(rows.stream().mapToInt(DocxDocumentParser::gridWidth).max().orElse(0));
        Map<Integer, Integer> columnMergePending = new HashMap<>();
        Map<Integer, ParseElement> columnMergeAnchor = new HashMap<>();
        int rowIndex = 0;
        for (XWPFTableRow row : rows) {
            int colIndex = 0;
            for (XWPFTableCell cell : row.getTableCells()) {
                int gridSpan = gridSpan(cell);
                STMerge.Enum vMerge = vMerge(cell);
                if (NullUtil.isNull(vMerge) || STMerge.RESTART.equals(vMerge)) {
                    ParseElement cellElement = ParseElement.of("t" + tableIndex + "c" + rowIndex + "_" + colIndex,
                            ElementType.TABLE_CELL);
                    cellElement.setText(cell.getText());
                    cellElement.setRow(rowIndex);
                    cellElement.setCol(colIndex);
                    cellElement.setColSpan(gridSpan > 1 ? gridSpan : null);
                    cellElement.setIsHeader(rowIndex == 0);
                    cellElement.setProvenance(new Provenance(fileId,
                            "office#document.xml/table[" + tableIndex + "]/cell[" + rowIndex + "," + colIndex + "]"));
                    appendCell(tableElement, cellElement);
                    if (STMerge.RESTART.equals(vMerge)) {
                        columnMergePending.put(colIndex, 1);
                        columnMergeAnchor.put(colIndex, cellElement);
                    }
                } else if (STMerge.CONTINUE.equals(vMerge) && columnMergePending.containsKey(colIndex)) {
                    columnMergePending.merge(colIndex, 1, Integer::sum);
                    mergeContinueText(columnMergeAnchor.get(colIndex), cell.getText());
                }
                colIndex += gridSpan;
            }
            rowIndex++;
        }
        // 回填 vMerge 行跨度（restart 单元格）
        for (ParseElement cell : tableElement.getCells()) {
            Integer pending = columnMergePending.get(cell.getCol());
            if (NullUtil.isNotNull(pending) && pending > 1) {
                cell.setRowSpan(pending);
            }
        }
        tableElement.setHeaderRow(settleHeaderRow(tableElement));
        return tableElement;
    }

    /** 行占的网格列数：各单元格 gridSpan 之和 */
    private static int gridWidth(XWPFTableRow row) {
        int width = 0;
        for (XWPFTableCell cell : row.getTableCells()) {
            width += gridSpan(cell);
        }
        return width;
    }

    /** vMerge=continue 格的文本并入其起始格（换行分隔，与多段落单元格同一形态），保证不丢 */
    private static void mergeContinueText(ParseElement anchor, String continueText) {
        if (NullUtil.isNull(anchor) || StrUtil.isBlank(continueText)) {
            return;
        }
        anchor.setText(StrUtil.isBlank(anchor.getText()) ? continueText.trim()
                : anchor.getText() + "\n" + continueText.trim());
    }

    /** 单元格横向跨度（无 gridSpan 记 1） */
    private static int gridSpan(XWPFTableCell cell) {
        CTTcPr tcPr = cell.getCTTc().getTcPr();
        return NullUtil.isNotNull(tcPr) && NullUtil.isNotNull(tcPr.getGridSpan())
                ? tcPr.getGridSpan().getVal().intValue() : 1;
    }

    /** 单元格纵向合并类型（无 vMerge 记 null） */
    private static STMerge.Enum vMerge(XWPFTableCell cell) {
        CTTcPr tcPr = cell.getCTTc().getTcPr();
        return NullUtil.isNotNull(tcPr) && NullUtil.isNotNull(tcPr.getVMerge())
                ? tcPr.getVMerge().getVal() : null;
    }
}
