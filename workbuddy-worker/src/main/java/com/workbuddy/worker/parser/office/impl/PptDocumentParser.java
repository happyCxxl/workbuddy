package com.workbuddy.worker.parser.office.impl;

import cn.hutool.core.util.StrUtil;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.common.domain.parse.Provenance;
import com.workbuddy.common.enums.parse.ElementType;
import com.workbuddy.common.enums.input.FileFormat;
import com.workbuddy.common.utils.NullUtil;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.office.AbstractPoiDocumentParser;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.PictureShape;
import org.apache.poi.sl.usermodel.Shape;
import org.apache.poi.sl.usermodel.Slide;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.sl.usermodel.TableCell;
import org.apache.poi.sl.usermodel.TableShape;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * PPT / PPTX 解析器：幻灯片对象模型走 POI 的 `sl.usermodel` 公共接口（XSLF 与 HSLF 两条读取路径只在开档处分流），
 * 产出 native 路 —— 每张幻灯片一条单元指标（页单位 = 幻灯片序号），形状按类型转元素：文本框与占位符文字成段落、
 * 表格成 TABLE（行列与表头判定复用 Office 统一口径）、图片成 IMAGE（引用 + needsOcr）并按页记一条 OCR_IMAGE 事实。
 */
@Slf4j
@Component
public class PptDocumentParser extends AbstractPoiDocumentParser {

    @Override
    public boolean supports(String mime) {
        return FileFormat.PPT.getMimeType().equals(mime) || FileFormat.PPTX.getMimeType().equals(mime);
    }

    @Override
    protected void parseNative(ParseSource source, byte[] data, ParseContext context) {
        String fileId = context.getFileRef().getFileId();
        String fileName = StrUtil.blankToDefault(context.getFileRef().getFileName(), "");
        boolean legacy = fileName.toLowerCase(Locale.ROOT).endsWith(".ppt");
        try (SlideShow<?, ?> show = open(data, legacy)) {
            List<String> unitTexts = new ArrayList<>();
            List<Integer> unitImages = new ArrayList<>();
            int slideNo = 0;
            for (Slide<?, ?> slide : show.getSlides()) {
                slideNo++;
                StringBuilder text = new StringBuilder();
                int paragraphs = 0;
                int tables = 0;
                int pictures = 0;
                for (Shape<?, ?> shape : slide.getShapes()) {
                    if (shape instanceof TextShape<?, ?> textShape) {
                        String shapeText = StrUtil.trimToEmpty(textShape.getText());
                        if (StrUtil.isNotBlank(shapeText)) {
                            text.append(shapeText);
                            source.getElements().add(paragraphElement("p" + slideNo + "_" + paragraphs, shapeText,
                                    slideNo, "office#slide[" + slideNo + "]/text[" + paragraphs + "]", fileId));
                            paragraphs++;
                        }
                    } else if (shape instanceof TableShape<?, ?> table) {
                        ParseElement tableElement = tableElement("t" + slideNo + "_" + tables, slideNo,
                                "office#slide[" + slideNo + "]/table[" + tables + "]", fileId);
                        fillTable(tableElement, table);
                        source.getElements().add(tableElement);
                        text.append(tableText(tableElement));
                        tables++;
                    } else if (shape instanceof PictureShape<?, ?> picture) {
                        source.getElements().add(imageElement("img" + slideNo + "_" + pictures,
                                assetRef(picture.getPictureData(), pictures), slideNo,
                                "office#slide[" + slideNo + "]/picture[" + pictures + "]", fileId));
                        pictures++;
                    }
                }
                if (pictures > 0) {
                    source.getFacts().add(fact("slide[" + slideNo + "]",
                            "嵌入图片仅引用无文字（本页 " + pictures + " 张）"));
                }
                unitTexts.add(text.toString());
                unitImages.add(pictures);
            }
            fillUnitMetrics(source, unitTexts, unitImages);
        } catch (Exception e) {
            log.warn("演示文稿解析失败, fileId={}", fileId, e);
            throw new IllegalStateException("演示文稿解析失败: " + e.getMessage(), e);
        }
    }

    /** 开档：二进制 ppt 走 HSLF，OOXML pptx 走 XSLF，之后统一按公共接口遍历 */
    private SlideShow<?, ?> open(byte[] data, boolean legacy) throws Exception {
        return legacy ? new HSLFSlideShow(new ByteArrayInputStream(data))
                : new XMLSlideShow(new ByteArrayInputStream(data));
    }

    /** 表格：行列取自实际单元格，表头判定走 Office 统一口径 */
    private void fillTable(ParseElement tableElement, TableShape<?, ?> table) {
        int rows = table.getNumberOfRows();
        tableElement.setRows(rows);
        int maxCols = 0;
        for (int r = 0; r < rows; r++) {
            maxCols = Math.max(maxCols, table.getNumberOfColumns());
            for (int c = 0; c < table.getNumberOfColumns(); c++) {
                TableCell<?, ?> cell = table.getCell(r, c);
                appendCell(tableElement, cellElement(r, c,
                        NullUtil.isNull(cell) ? "" : StrUtil.trimToEmpty(cell.getText())));
            }
        }
        tableElement.setCols(maxCols);
        tableElement.setHeaderRow(settleHeaderRow(tableElement));
    }

    private ParseElement paragraphElement(String id, String text, int slideNo, String path, String fileId) {
        ParseElement element = ParseElement.of(id, ElementType.PARAGRAPH);
        element.setText(text);
        element.setPage(slideNo);
        element.setProvenance(new Provenance(fileId, path));
        return element;
    }

    private ParseElement tableElement(String id, int slideNo, String path, String fileId) {
        ParseElement element = ParseElement.of(id, ElementType.TABLE);
        element.setPage(slideNo);
        element.setProvenance(new Provenance(fileId, path));
        return element;
    }

    private ParseElement imageElement(String id, String assetRef, int slideNo, String path, String fileId) {
        ParseElement element = ParseElement.of(id, ElementType.IMAGE);
        element.setAssetRef(assetRef);
        element.setNeedsOcr(true);
        element.setPage(slideNo);
        element.setProvenance(new Provenance(fileId, path));
        return element;
    }

    private ParseElement cellElement(int row, int col, String text) {
        ParseElement cell = ParseElement.of(ElementType.TABLE_CELL);
        cell.setText(text);
        cell.setRow(row);
        cell.setCol(col);
        return cell;
    }

    /** 表格文本（单元指标用）：各格文本拼接 */
    private String tableText(ParseElement tableElement) {
        StringBuilder text = new StringBuilder();
        for (ParseElement cell : NullUtil.isNull(tableElement.getCells()) ? List.<ParseElement>of()
                : tableElement.getCells()) {
            text.append(StrUtil.blankToDefault(cell.getText(), ""));
        }
        return text.toString();
    }

    /** 图片引用：`image-序号.扩展名`，扩展名按内容类型推断、取不到再按字节头兜底、最终给 png */
    private String assetRef(PictureData pictureData, int index) {
        return "image-" + index + "." + suffix(pictureData);
    }

    private String suffix(PictureData pictureData) {
        if (NullUtil.isNull(pictureData)) {
            return "png";
        }
        String contentType = StrUtil.blankToDefault(pictureData.getContentType(), "").toLowerCase(Locale.ROOT);
        if (contentType.contains("jpeg") || contentType.contains("jpg")) {
            return "jpg";
        }
        if (contentType.contains("gif")) {
            return "gif";
        }
        if (contentType.contains("bmp")) {
            return "bmp";
        }
        if (contentType.contains("tiff")) {
            return "tiff";
        }
        if (contentType.contains("png")) {
            return "png";
        }
        return suffixByMagic(pictureData.getData());
    }

    /** 字节头兜底：PNG / JPEG / GIF / BMP 之外一律按 png */
    private String suffixByMagic(byte[] bytes) {
        if (NullUtil.isNull(bytes) || bytes.length < 3) {
            return "png";
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "jpg";
        }
        if (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return "gif";
        }
        if (bytes[0] == 'B' && bytes[1] == 'M') {
            return "bmp";
        }
        return "png";
    }
}
