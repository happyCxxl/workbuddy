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
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.PicturesTable;
import org.apache.poi.hwpf.model.StyleDescription;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.HeaderStories;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableIterator;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/**
 * DOC（HWPF 二进制）原生结构解析器（POI 路径）。
 * 能力弱于 DOCX：表格合并信息读不到，只取行列文本；嵌入图片按序记录引用（定位到段落成本高）。
 */
@Slf4j
@Component
public class DocDocumentParser extends AbstractPoiDocumentParser {

    /**
     * 是否支持该媒体类型。
     *
     * @param mimeType 媒体类型
     * @return 支持 DOC 为 true
     */
    @Override
    public boolean supports(String mimeType) {
        return FileFormat.DOC.getMimeType().equals(mimeType);
    }

    /**
     * DOC（HWPF 二进制）主流程：段落（表格内段落跳过，由 TableIterator 输出）、图片引用与表格。
     *
     * @param source  待填充的原生解析结果
     * @param data    文件字节（已读全）
     * @param context 解析上下文
     */
    @Override
    protected void parseNative(ParseSource source, byte[] data, ParseContext context) {
        String fileId = context.getFileRef().getFileId();
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(data))) {
            Range range = doc.getRange();
            int paragraphIndex = 0;
            for (int i = 0; i < range.numParagraphs(); i++) {
                Paragraph paragraph = range.getParagraph(i);
                if (paragraph.isInTable()) {
                    // 表格内容由 TableIterator 统一输出
                    continue;
                }
                ParseElement element = toDocParagraphElement(doc, paragraph, paragraphIndex, fileId);
                if (NullUtil.isNotNull(element)) {
                    source.getElements().add(element);
                }
                paragraphIndex++;
            }
            // 嵌入图片（PicturesTable 全表引用；定位到段落成本高，按序记录）；OCR_IMAGE 事实整档一条
            PicturesTable pictures = doc.getPicturesTable();
            for (int p = 0; p < pictures.getAllPictures().size(); p++) {
                Picture picture = pictures.getAllPictures().get(p);
                ParseElement image = ParseElement.of("img" + p, ElementType.IMAGE);
                image.setAssetRef(StrUtil.blankToDefault(picture.getDescription(), "image-" + p));
                image.setNeedsOcr(true);
                image.setProvenance(new Provenance(fileId, "office#document/picture[" + p + "]"));
                source.getElements().add(image);
            }
            if (!pictures.getAllPictures().isEmpty()) {
                source.getFacts().add(fact("picture",
                        "嵌入图片仅引用无文字（" + pictures.getAllPictures().size() + " 张）"));
            }
            // 页眉页脚（首页故事）：与 DOCX 口径一致，独立产出 HEADER/FOOTER 元素
            appendDocHeaderFooter(source, doc, fileId);
            // 表格（HWPF 结构支持弱；合并信息读不到，只取行列文本）
            TableIterator tableIterator = new TableIterator(range);
            int tableIndex = 0;
            while (tableIterator.hasNext()) {
                Table table = tableIterator.next();
                source.getElements().add(toDocTableElement(table, tableIndex, fileId));
                tableIndex++;
            }
            fillVirtualPageMetrics(source, context.getProperties().getOfficePageChars());
        } catch (Exception e) {
            log.warn("===> DocDocumentParser DOC 解析失败, fileId={}", fileId, e);
            throw new IllegalStateException("DOC 解析失败: " + e.getMessage(), e);
        }
    }

    /** DOC 段落元素：样式名 + 目录候选 + 字体事实取首 run；HWPF 字号为半点单位，换算 pt。 */
    private ParseElement toDocParagraphElement(HWPFDocument document, Paragraph paragraph, int index,
                                               String fileId) {
        String text = stripFieldCodes(paragraph.text()).trim();
        if (StrUtil.isBlank(text)) {
            return null;
        }
        ParseElement element = ParseElement.of("p" + index, ElementType.PARAGRAPH);
        element.setText(text);
        element.setStyle(docStyleName(document, paragraph));
        element.setTocCandidate(TocLineFeature.isTocLine(text));
        if (paragraph.numCharacterRuns() > 0) {
            CharacterRun run = paragraph.getCharacterRun(0);
            FontInfo font = new FontInfo();
            font.setName(run.getFontName());
            // HWPF 字号单位为半点（half-point），换算 pt
            int halfPoints = run.getFontSize();
            font.setSize(halfPoints > 0 ? halfPoints / 2.0 : null);
            font.setBold(run.isBold());
            element.setFont(font);
        }
        element.setProvenance(new Provenance(fileId, "office#document/paragraph[" + index + "]"));
        return element;
    }

    /** DOC 页眉页脚（HeaderStories 的首页故事）→ HEADER/FOOTER 元素；读取异常不阻断整档解析 */
    private void appendDocHeaderFooter(ParseSource source, HWPFDocument document, String fileId) {
        try {
            HeaderStories stories = new HeaderStories(document);
            addStoryElement(source, stories.getFirstHeader(), ElementType.HEADER, "h0", "doc#header[0]", fileId);
            addStoryElement(source, stories.getFirstFooter(), ElementType.FOOTER, "f0", "doc#footer[0]", fileId);
        } catch (RuntimeException e) {
            log.warn("===> DocDocumentParser DOC 页眉页脚读取失败, fileId={}", fileId, e);
        }
    }

    /** 单个页眉/页脚故事 → 元素（文本非空才产出） */
    private void addStoryElement(ParseSource source, String storyText, ElementType type, String id, String path,
                                 String fileId) {
        if (StrUtil.isBlank(storyText)) {
            return;
        }
        ParseElement element = ParseElement.of(id, type);
        element.setText(storyText.trim());
        element.setProvenance(new Provenance(fileId, path));
        source.getElements().add(element);
    }

    /**
     * 域代码剥离：HWPF 的 `text()` 会把域的起始（0x13）、分隔（0x14）、结束（0x15）控制符一并给出，
     * 起始到分隔之间是域指令（`PAGE` / `HYPERLINK` 之类），这里丢掉指令、保留域结果文本，并清掉控制符。
     *
     * @param raw 原始段落/单元格文本
     * @return 剥离后的文本
     */
    static String stripFieldCodes(String raw) {
        if (StrUtil.isEmpty(raw)) {
            return raw;
        }
        StringBuilder out = new StringBuilder();
        boolean inInstruction = false;
        for (char ch : raw.toCharArray()) {
            if (ch == '\u0013') {
                inInstruction = true;
            } else if (ch == '\u0014' || ch == '\u0015') {
                inInstruction = false;
            } else if (!inInstruction) {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /** 段落样式名：HWPF 样式表按索引取名字；索引越界或读取异常给 null（不阻断解析） */
    private static String docStyleName(HWPFDocument document, Paragraph paragraph) {
        try {
            StyleDescription description = document.getStyleSheet().getStyleDescription(paragraph.getStyleIndex());
            return NullUtil.isNull(description) ? null : description.getName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** DOC 表格元素（HWPF 结构支持弱：合并信息读不到，只取行列文本）。 */
    private ParseElement toDocTableElement(Table table, int tableIndex, String fileId) {
        ParseElement tableElement = ParseElement.of("t" + tableIndex, ElementType.TABLE);
        tableElement.setRows(table.numRows());
        int maxCols = 0;
        for (int r = 0; r < table.numRows(); r++) {
            org.apache.poi.hwpf.usermodel.TableRow row = table.getRow(r);
            maxCols = Math.max(maxCols, row.numCells());
            for (int c = 0; c < row.numCells(); c++) {
                ParseElement cellElement = ParseElement.of("t" + tableIndex + "c" + r + "_" + c,
                        ElementType.TABLE_CELL);
                cellElement.setText(stripFieldCodes(row.getCell(c).text()).trim());
                cellElement.setRow(r);
                cellElement.setCol(c);
                cellElement.setIsHeader(r == 0);
                cellElement.setProvenance(new Provenance(fileId,
                        "office#document/table[" + tableIndex + "]/cell[" + r + "," + c + "]"));
                appendCell(tableElement, cellElement);
            }
        }
        tableElement.setCols(maxCols);
        tableElement.setHeaderRow(settleHeaderRow(tableElement));
        return tableElement;
    }
}
