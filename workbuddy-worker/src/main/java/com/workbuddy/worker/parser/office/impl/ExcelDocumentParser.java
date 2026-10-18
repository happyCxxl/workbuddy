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
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.PictureData;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Shape;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Excel（XLS/XLSX）原生结构解析器（POI 路径）。
 * 每个 sheet 产出 TABLE 元素；定位 = sheet + 行列（无页码概念）；
 * 合并区域 origin 单元格记 span、其余跳过；公式格取缓存值。
 */
@Slf4j
@Component
public class ExcelDocumentParser extends AbstractPoiDocumentParser {

    /**
     * 是否支持该媒体类型。
     *
     * @param mimeType 媒体类型
     * @return 支持 XLS 或 XLSX 为 true
     */
    @Override
    public boolean supports(String mimeType) {
        return FileFormat.XLS.getMimeType().equals(mimeType)
                || FileFormat.XLSX.getMimeType().equals(mimeType);
    }

    /**
     * Excel 主流程：逐 sheet 产出 TABLE 元素（定位 = sheet 与行列，无页码概念）。
     *
     * @param source  待填充的原生解析结果
     * @param data    文件字节（已读全）
     * @param context 解析上下文
     */
    @Override
    protected void parseNative(ParseSource source, byte[] data, ParseContext context) {
        String fileId = context.getFileRef().getFileId();
        boolean xlsx = FileFormat.XLSX.getMimeType().equals(context.getFileRef().getMimeType());
        try (Workbook workbook = xlsx ? new XSSFWorkbook(new ByteArrayInputStream(data))
                : new HSSFWorkbook(new ByteArrayInputStream(data))) {
            DataFormatter formatter = new DataFormatter();
            // 公式格回落取缓存值时按缓存结果类型格式化，避免落成公式串
            formatter.setUseCachedValuesForFormulaCells(true);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            int sheetCount = workbook.getNumberOfSheets();
            List<String> sheetTexts = new ArrayList<>();
            List<Integer> sheetImages = new ArrayList<>();
            for (int s = 0; s < sheetCount; s++) {
                ParseElement tableElement = toSheetTableElement(workbook, formatter, evaluator, s, fileId);
                source.getElements().add(tableElement);
                appendSheetHeaderFooter(source, workbook.getSheetAt(s), s, fileId);
                appendSheetPictures(source, workbook.getSheetAt(s), fileId);
                sheetTexts.add(tableText(tableElement));
                sheetImages.add(pictureCount(workbook.getSheetAt(s)));
            }
            fillUnitMetrics(source, sheetTexts, sheetImages);
            appendXlsFormulaNote(source, workbook);
        } catch (Exception e) {
            log.warn("Excel 解析失败, fileId={}", fileId, e);
            throw new IllegalStateException("Excel 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 工作表图片：逐张产 `IMAGE` 元素（`assetRef` 取图片文件名、`needsOcr=true`）并按表记一条
     * `OCR_IMAGE` 事实（证据带本表张数），与 PDF / DOCX 的图片口径一致。
     */
    private void appendSheetPictures(ParseSource source, Sheet sheet, String fileId) {
        Drawing<?> drawing = sheet.getDrawingPatriarch();
        if (NullUtil.isNull(drawing)) {
            return;
        }
        int index = 0;
        for (Shape shape : drawing) {
            if (!(shape instanceof Picture picture)) {
                continue;
            }
            ParseElement image = ParseElement.of("img" + index, ElementType.IMAGE);
            PictureData data = picture.getPictureData();
            image.setAssetRef(NullUtil.isNull(data) ? "image-" + index
                    : "image-" + index + "." + data.suggestFileExtension());
            image.setNeedsOcr(true);
            image.setProvenance(new Provenance(fileId,
                    "office#sheet[" + sheet.getSheetName() + "]/picture[" + index + "]"));
            source.getElements().add(image);
            index++;
        }
        if (index > 0) {
            source.getFacts().add(fact("sheet[" + sheet.getSheetName() + "]",
                    "嵌入图片仅引用无文字（本表 " + index + " 张）"));
        }
    }

    /** 单 sheet 表格元素：合并区域 origin 单元格记 span、其余跳过；取值走 cellText。 */
    private ParseElement toSheetTableElement(Workbook workbook, DataFormatter formatter,
                                             FormulaEvaluator evaluator, int sheetIndex, String fileId) {
        Sheet sheet = workbook.getSheetAt(sheetIndex);
        String sheetName = sheet.getSheetName();
        ParseElement tableElement = ParseElement.of("sheet" + sheetIndex, ElementType.TABLE);
        tableElement.setPage(sheetIndex);
        tableElement.setSheetName(sheetName);
        // 合并区域：origin 单元格记 span，被覆盖格记归属（其文本并入 origin，不丢）
        Map<Long, int[]> mergeSpans = new HashMap<>();
        java.util.Set<Long> covered = new java.util.HashSet<>();
        Map<Long, Long> coveredAnchor = new HashMap<>();
        for (CellRangeAddress region : sheet.getMergedRegions()) {
            int rowSpan = region.getLastRow() - region.getFirstRow() + 1;
            int colSpan = region.getLastColumn() - region.getFirstColumn() + 1;
            mergeSpans.put(key(region.getFirstRow(), region.getFirstColumn()), new int[]{rowSpan, colSpan});
            for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
                for (int c = region.getFirstColumn(); c <= region.getLastColumn(); c++) {
                    if (r != region.getFirstRow() || c != region.getFirstColumn()) {
                        covered.add(key(r, c));
                        coveredAnchor.put(key(r, c), key(region.getFirstRow(), region.getFirstColumn()));
                    }
                }
            }
        }
        int firstRow = sheet.getFirstRowNum();
        int lastRow = sheet.getLastRowNum();
        int firstDataRow = -1;
        int maxCol = 0;
        for (int r = firstRow; r <= lastRow; r++) {
            Row row = sheet.getRow(r);
            if (NullUtil.isNull(row)) {
                continue;
            }
            for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
                Cell cell = row.getCell(c);
                if (NullUtil.isNull(cell)) {
                    continue;
                }
                if (covered.contains(key(r, c))) {
                    String coveredText = cellText(cell, formatter, evaluator);
                    if (StrUtil.isNotBlank(coveredText)) {
                        mergeCoveredText(tableElement, coveredAnchor.get(key(r, c)), coveredText);
                    }
                    continue;
                }
                String text = cellText(cell, formatter, evaluator);
                if (StrUtil.isBlank(text)) {
                    continue;
                }
                if (firstDataRow < 0) {
                    firstDataRow = r;
                }
                ParseElement cellElement = ParseElement.of("s" + sheetIndex + "c" + r + "_" + c,
                        ElementType.TABLE_CELL);
                cellElement.setText(text);
                cellElement.setRow(r);
                cellElement.setCol(c);
                // 表头行 = 首个有非空文本的行（不是 sheet 的首个存在行），isHeader 与该行一致
                cellElement.setIsHeader(r == firstDataRow);
                int[] span = mergeSpans.get(key(r, c));
                if (NullUtil.isNotNull(span)) {
                    cellElement.setRowSpan(span[0] > 1 ? span[0] : null);
                    cellElement.setColSpan(span[1] > 1 ? span[1] : null);
                }
                maxCol = Math.max(maxCol, c + (NullUtil.isNull(span) ? 1 : span[1]));
                cellElement.setProvenance(new Provenance(fileId,
                        "office#sheet[" + sheetName + "]/cell[" + r + "," + c + "]"));
                appendCell(tableElement, cellElement);
            }
        }
        tableElement.setRows(firstDataRow >= 0 ? lastRow - firstRow + 1 : 0);
        tableElement.setCols(firstDataRow >= 0 ? maxCol : 0);
        tableElement.setHeaderRow(firstDataRow >= 0 ? firstDataRow : null);
        return tableElement;
    }

    /**
     * 单元格取值：普通格走 DataFormatter（含日期/百分比等显示格式）；
     * 公式格先求值，求值不可用（未支持函数等）时回落到文件里的缓存值，两者都没有则留空（不落公式串）。
     */
    private String cellText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (!CellType.FORMULA.equals(cell.getCellType())) {
            return formatter.formatCellValue(cell);
        }
        // 显示值优先：公式格先取文件里缓存的显示值（上次保存时的结果），缓存缺失或为空才现场求值
        String cached = hasCachedValue(cell) ? cachedText(cell, formatter) : "";
        if (StrUtil.isNotBlank(cached)) {
            return cached;
        }
        return evaluate(cell, formatter, evaluator);
    }

    /** 缓存显示值：求值器传 null，POI 直接格式化文件里的缓存结果 */
    private String cachedText(Cell cell, DataFormatter formatter) {
        try {
            return formatter.formatCellValue(cell, null);
        } catch (RuntimeException e) {
            log.debug("Excel 公式缓存值读取失败, sheet={}, cell={}", cell.getSheet().getSheetName(),
                    cell.getAddress(), e);
            return "";
        }
    }

    /** 缓存结果是否在文件里：xlsx 看 <v> 元素（缺失时 POI 的缓存类型仍报数值，取出来是 0）；xls 的公式记录总带数值缓存 */
    private static boolean hasCachedValue(Cell cell) {
        return !(cell instanceof XSSFCell xssfCell) || xssfCell.getCTCell().isSetV();
    }

    /** 公式求值（求值失败留空，由 cellText 回落缓存值） */
    private String evaluate(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        try {
            return formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException e) {
            log.debug("Excel 公式求值失败, sheet={}, cell={}", cell.getSheet().getSheetName(),
                    cell.getAddress(), e);
            return "";
        }
    }

    /** sheet 已产出单元格的文本（单元指标输入） */
    private static String tableText(ParseElement tableElement) {
        return NullUtil.isNull(tableElement.getCells()) ? "" : tableElement.getCells().stream()
                .map(ParseElement::getText)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n"));
    }

    /** sheet 内嵌图片数（图形层里的图片形状；图表不计） */
    private static int pictureCount(Sheet sheet) {
        Drawing<?> drawing = sheet.getDrawingPatriarch();
        if (NullUtil.isNull(drawing)) {
            return 0;
        }
        int count = 0;
        for (Shape shape : drawing) {
            if (shape instanceof Picture) {
                count++;
            }
        }
        return count;
    }

    /** 单个工作表页眉页脚 → HEADER/FOOTER 元素（与 DOCX 口径一致：文本非空才产出） */
    private void appendSheetHeaderFooter(ParseSource source, Sheet sheet, int sheetIndex, String fileId) {
        addSheetPart(source, sheetHeaderFooterText(sheetPartText(sheet.getHeader().getLeft(),
                        sheet.getHeader().getCenter(), sheet.getHeader().getRight())),
                ElementType.HEADER, "h" + sheetIndex,
                "office#sheet[" + sheet.getSheetName() + "]/header", fileId);
        addSheetPart(source, sheetHeaderFooterText(sheetPartText(sheet.getFooter().getLeft(),
                        sheet.getFooter().getCenter(), sheet.getFooter().getRight())),
                ElementType.FOOTER, "f" + sheetIndex,
                "office#sheet[" + sheet.getSheetName() + "]/footer", fileId);
    }

    /** 页眉页脚三区拼接（POI 按左中右三区承载文本） */
    private static String sheetPartText(String left, String center, String right) {
        return StrUtil.blankToDefault(left, "") + " " + StrUtil.blankToDefault(center, "") + " "
                + StrUtil.blankToDefault(right, "");
    }

    /** 页眉/页脚元素入列 */
    private void addSheetPart(ParseSource source, String text, ElementType type, String id, String path,
                              String fileId) {
        if (StrUtil.isBlank(text)) {
            return;
        }
        ParseElement element = ParseElement.of(id, type);
        element.setText(text);
        element.setProvenance(new Provenance(fileId, path));
        source.getElements().add(element);
    }

    /**
     * 旧格式（xls）公式缓存留痕：HSSF 的公式记录总带数值缓存，从未计算过的公式取出来是 0、与真的 0 无法区分。
     * 有公式格时把这条口径写进路说明（不产信号事实，避免被判定成表格或图片问题）。
     */
    private void appendXlsFormulaNote(ParseSource source, Workbook workbook) {
        if (!(workbook instanceof HSSFWorkbook)) {
            return;
        }
        int formulaCells = 0;
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            Sheet sheet = workbook.getSheetAt(s);
            for (Row row : sheet) {
                for (Cell cell : row) {
                    if (CellType.FORMULA.equals(cell.getCellType())) {
                        formulaCells++;
                    }
                }
            }
        }
        if (formulaCells == 0) {
            return;
        }
        String note = StrUtil.blankToDefault(source.getNote(), "");
        source.setNote((note.isEmpty() ? "" : note + "; ")
                + "旧格式公式缓存可能未计算（本档 " + formulaCells + " 个公式格按缓存值取值）");
    }

    /**
     * 页眉页脚文本去格式码：`&L` / `&C` / `&R` 段标记转为分隔、`&"字体"` 与其余 `&` 开头的格式码去掉，
     * 连续空白合并；只保留人可读文本。
     */
    static String sheetHeaderFooterText(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "";
        }
        String cleaned = raw.replaceAll("&[LCR]", " ")
                .replaceAll("&\"[^\"]*\"", " ")
                .replaceAll("&[A-Za-z0-9]+", " ")
                .replace("&&", "&");
        return cleaned.replaceAll("\\s+", " ").trim();
    }

    /** 行列坐标 → 单键（高 32 位行、低 32 位列）。 */
    private long key(int row, int col) {
        return ((long) row << 32) | (col & 0xFFFFFFFFL);
    }

    /** 被合并覆盖格的文本并入锚格（换行分隔，与单元格多段落文本同一形态），保证不丢 */
    private void mergeCoveredText(ParseElement tableElement, Long anchorKey, String coveredText) {
        if (NullUtil.isNull(anchorKey)) {
            return;
        }
        int anchorRow = (int) (anchorKey >> 32);
        int anchorCol = (int) (anchorKey & 0xFFFFFFFFL);
        for (ParseElement cell : tableElement.getCells()) {
            if (NullUtil.isNotNull(cell.getRow()) && cell.getRow() == anchorRow
                    && NullUtil.isNotNull(cell.getCol()) && cell.getCol() == anchorCol) {
                cell.setText(StrUtil.isBlank(cell.getText()) ? coveredText : cell.getText() + "\n" + coveredText);
                return;
            }
        }
    }
}
