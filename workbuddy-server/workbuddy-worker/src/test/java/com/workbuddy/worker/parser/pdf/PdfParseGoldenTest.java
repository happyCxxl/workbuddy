package com.workbuddy.worker.parser.pdf;

import cn.hutool.json.JSONUtil;
import com.workbuddy.common.domain.parse.ParseSource;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PDF 解析黄金快照：同一批样本的 {@link ParseSource} 规范化 JSON 必须与基线逐行一致。
 *
 * <p>基线由 {@code GOLDEN_UPDATE=true} 生成；日常只读，用于重构前后逐字段对拍。
 */
class PdfParseGoldenTest {

    /** 样本清单：多栏、扫描、混合、无框表 */
    private static final List<String> SAMPLES = List.of(
            "multi-column.pdf",
            "scanned.pdf",
            "mixed.pdf",
            "borderless-table.pdf");

    private static final Path GOLDEN_DIR = Path.of("src", "test", "resources", "pdf-golden");

    private final PdfParserTestFixture fixture = new PdfParserTestFixture();

    /** 样本解析产物应与黄金快照逐行一致（{@code pdfParseShouldMatchGoldenSnapshots}）。 */
    @Test
    void pdfParseShouldMatchGoldenSnapshots() throws Exception {
        boolean update = "true".equalsIgnoreCase(System.getenv("GOLDEN_UPDATE"));
        Files.createDirectories(GOLDEN_DIR);
        for (String sample : SAMPLES) {
            ParseSource source = fixture.parse(sampleBytes(sample));
            String actual = JSONUtil.toJsonPrettyStr(source);
            Path golden = GOLDEN_DIR.resolve(sample + ".json");
            if (update) {
                Files.writeString(golden, actual, StandardCharsets.UTF_8);
            }
            assertSameLines(sample, Files.readString(golden, StandardCharsets.UTF_8), actual);
        }
    }

    /** 读取样本字节；缺失样本直接失败，避免快照空跑。 */
    private byte[] sampleBytes(String sample) throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("pdf-samples/" + sample)) {
            if (input == null) {
                throw new IllegalStateException("找不到 PDF 样本: " + sample);
            }
            return input.readAllBytes();
        }
    }

    /** 逐行比较，失败时报告第一处差异，便于定位字段/顺序变化。 */
    private void assertSameLines(String sample, String expected, String actual) {
        String[] expectedLines = expected.split("\\R", -1);
        String[] actualLines = actual.split("\\R", -1);
        int max = Math.max(expectedLines.length, actualLines.length);
        for (int index = 0; index < max; index++) {
            String left = index < expectedLines.length ? expectedLines[index] : "<缺失>";
            String right = index < actualLines.length ? actualLines[index] : "<缺失>";
            if (!left.equals(right)) {
                fail(sample + " 黄金快照第一处差异在第 " + (index + 1) + " 行\n期望: " + left
                        + "\n实际: " + right);
            }
        }
        assertEquals(expected, actual, sample + " 黄金快照不一致");
    }
}
