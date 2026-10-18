import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.workbuddy.common.domain.input.FileReference;
import com.workbuddy.common.domain.parse.ParseElement;
import com.workbuddy.common.domain.parse.ParseSource;
import com.workbuddy.worker.parser.DocumentParserPort;
import com.workbuddy.worker.parser.ParseContext;
import com.workbuddy.worker.parser.ParseProperties;
import com.workbuddy.worker.parser.office.impl.DocDocumentParser;
import com.workbuddy.worker.parser.office.impl.DocxDocumentParser;
import com.workbuddy.worker.parser.office.impl.ExcelDocumentParser;
import com.workbuddy.worker.parser.office.impl.PptDocumentParser;

/**
 * 用仓库里的解析器核对 samples/office-parse-* 样例：逐个文件打印关键产物（元素、表格行列与表头行、
 * 页级指标、事实与路说明），用于人工复核 OFF-01~OFF-15 的修复效果。运行方式见同目录 README。
 */
public final class VerifyOfficeSamples {

    public static void main(String[] args) throws Exception {
        Path samples = Path.of("samples");
        if (!Files.isDirectory(samples)) {
            System.out.println("找不到 samples/ 目录");
            return;
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(samples)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().contains("office-parse-"))
                    .filter(VerifyOfficeSamples::isDocument)
                    .sorted()
                    .forEach(files::add);
        }
        for (Path file : files) {
            System.out.println("=== " + samples.relativize(file));
            try {
                print(parse(file));
            } catch (Exception e) {
                System.out.println("  解析异常: " + e);
            }
        }
    }

    private static boolean isDocument(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".xlsx") || name.endsWith(".xls") || name.endsWith(".docx") || name.endsWith(".pptx");
    }

    private static ParseSource parse(Path file) throws Exception {
        byte[] data = Files.readAllBytes(file);
        String name = file.getFileName().toString();
        String lower = name.toLowerCase();
        DocumentParserPort parser;
        String mime;
        if (lower.endsWith(".xlsx")) {
            parser = new ExcelDocumentParser();
            mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        } else if (lower.endsWith(".xls")) {
            parser = new ExcelDocumentParser();
            mime = "application/vnd.ms-excel";
        } else if (lower.endsWith(".docx")) {
            parser = new DocxDocumentParser();
            mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        } else if (lower.endsWith(".pptx")) {
            parser = new PptDocumentParser();
            mime = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        } else {
            parser = new DocDocumentParser();
            mime = "application/msword";
        }
        ParseContext context = new ParseContext();
        FileReference fileRef = new FileReference();
        fileRef.setFileId("sample");
        fileRef.setFileName(name);
        fileRef.setMimeType(mime);
        context.setFileRef(fileRef);
        context.setInputStream(new ByteArrayInputStream(data));
        context.setProperties(new ParseProperties());
        return parser.parse(context);
    }

    private static void print(ParseSource source) {
        System.out.println("  单元数=" + source.getUnitCount() + "  元素数=" + source.getElements().size()
                + (source.getNote() == null ? "" : "  路说明=" + source.getNote()));
        for (ParseElement element : source.getElements()) {
            StringBuilder line = new StringBuilder("  - ").append(element.getType());
            if (element.getPage() != null) {
                line.append(" page=").append(element.getPage());
            }
            if (element.getText() != null) {
                line.append(" text=").append(shorten(element.getText()));
            }
            if (element.getAssetRef() != null) {
                line.append(" asset=").append(element.getAssetRef());
            }
            if ("TABLE".equals(element.getType())) {
                line.append(" rows=").append(element.getRows()).append(" cols=").append(element.getCols())
                        .append(" headerRow=").append(element.getHeaderRow())
                        .append(" cells=").append(element.getCells() == null ? 0 : element.getCells().size());
            }
            if (element.getRow() != null) {
                line.append(" row=").append(element.getRow()).append(" col=").append(element.getCol())
                        .append(" isHeader=").append(element.getIsHeader());
            }
            if (element.getRowSpan() != null) {
                line.append(" rowSpan=").append(element.getRowSpan());
            }
            if (element.getColSpan() != null) {
                line.append(" colSpan=").append(element.getColSpan());
            }
            if (element.getProvenance() != null) {
                line.append("  溯源=").append(element.getProvenance().getPath());
            }
            System.out.println(line);
        }
        source.getPageMetrics().forEach(metric -> System.out.println("  # 指标 page=" + metric.getPage()
                + " chars=" + metric.getCharCount() + " images=" + metric.getImageCount()));
        source.getFacts().forEach(fact -> System.out.println("  ! 事实 " + fact.getType() + " "
                + fact.getRegion() + " " + fact.getEvidence()));
    }

    private static String shorten(String text) {
        String flat = text.replace("\n", "\\n");
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }
}
