import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;

/**
 * 生成 openpyxl / python-docx 写不出的 Office 复核样例（落到 samples/office-parse-NN/）：
 * 合并区被覆盖格带文本的 xlsx、vMerge=continue 格带文本的 docx、公式缓存值与现算值不同的 xlsx、
 * 旧格式 .xls 公式缓存样例。运行方式见同目录 README。
 */
public final class GenOfficePoiSamples {

    private static final String SAMPLES = "samples";

    public static void main(String[] args) throws Exception {
        gen08CoveredCellXlsx();
        gen08VMergeContinueDocx();
        gen12CachedFormulaXlsx();
        gen13LegacyFormulaXls();
        System.out.println("POI 样例已生成到 " + SAMPLES + "/office-parse-08|12|13");
    }

    /** OFF-08 上半：横向合并 A2:C2，被覆盖的 B2 / C2 各带自己的文本 */
    private static void gen08CoveredCellXlsx() throws Exception {
        Path dir = dir("office-parse-08");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("覆盖文本");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("列A");
            header.createCell(1).setCellValue("列B");
            header.createCell(2).setCellValue("列C");
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 2));
            Row merged = sheet.createRow(1);
            merged.createCell(0).setCellValue("合并块");
            merged.createCell(1).setCellValue("被覆盖值1");
            merged.createCell(2).setCellValue("被覆盖值2");
            write(workbook, dir.resolve("01-横向合并被覆盖格带文本.xlsx").toFile());
        }
    }

    /** OFF-08 下半：首列第 2 行 restart、第 3 行 continue，且 continue 格自带文本 */
    private static void gen08VMergeContinueDocx() throws Exception {
        Path dir = dir("office-parse-08");
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFTable table = doc.createTable(3, 2);
            table.getRow(0).getCell(0).setText("评分项");
            table.getRow(0).getCell(1).setText("分值");
            table.getRow(1).getCell(0).setText("A1 报价");
            table.getRow(1).getCell(1).setText("30");
            table.getRow(2).getCell(0).setText("续接文本");
            table.getRow(2).getCell(1).setText("15");
            vMerge(table.getRow(1).getCell(0), STMerge.RESTART);
            vMerge(table.getRow(2).getCell(0), STMerge.CONTINUE);
            try (OutputStream out = new FileOutputStream(dir.resolve("02-纵向合并续接格带文本.docx").toFile())) {
                doc.write(out);
            }
        }
    }

    /** OFF-12：公式 1+1 但缓存 <v> 写成 7（解析应取 7，而非现算的 2） */
    private static void gen12CachedFormulaXlsx() throws Exception {
        Path dir = dir("office-parse-12");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("公式缓存");
            sheet.createRow(0).createCell(0).setCellValue("指标");
            sheet.createRow(0).createCell(1).setCellValue("值");
            XSSFCell formula = (XSSFCell) sheet.createRow(1).createCell(1);
            formula.setCellFormula("1+1");
            formula.getCTCell().setV("7");
            XSSFCell plain = (XSSFCell) sheet.createRow(2).createCell(1);
            plain.setCellFormula("2*3");
            write(workbook, dir.resolve("01-公式缓存7现算2.xlsx").toFile());
        }
    }

    /** OFF-13：.xls（HSSF）公式缓存不可辨别 —— 含一个从未计算过的公式格 */
    private static void gen13LegacyFormulaXls() throws Exception {
        Path dir = dir("office-parse-13");
        try (HSSFWorkbook workbook = new HSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("旧格式");
            sheet.createRow(0).createCell(0).setCellValue("指标");
            sheet.createRow(0).createCell(1).setCellValue("值");
            sheet.createRow(1).createCell(0).setCellValue("合计");
            sheet.createRow(1).createCell(1).setCellFormula("1+1");
            write(workbook, dir.resolve("01-旧格式公式缓存.xls").toFile());
        }
    }

    private static void vMerge(XWPFTableCell cell, STMerge.Enum value) {
        cell.getCTTc().addNewTcPr().addNewVMerge().setVal(value);
    }

    private static Path dir(String name) throws Exception {
        Path path = Path.of(SAMPLES, name);
        Files.createDirectories(path);
        return path;
    }

    private static void write(Workbook workbook, File file) throws Exception {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                OutputStream out = new FileOutputStream(file)) {
            workbook.write(buffer);
            out.write(buffer.toByteArray());
        }
        workbook.close();
    }
}
