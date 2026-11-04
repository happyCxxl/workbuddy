package com.workbuddy.worker.parser.pdf.store;

import com.workbuddy.common.domain.parse.signal.PageMetric;
import com.workbuddy.worker.parser.pdf.model.CharInfo;
import com.workbuddy.worker.parser.pdf.model.PageContent;
import com.workbuddy.worker.parser.pdf.model.PageLine;
import com.workbuddy.worker.parser.pdf.model.PdfPageGeometry;
import com.workbuddy.worker.parser.pdf.model.Region;
import com.workbuddy.worker.parser.pdf.model.Segment;
import com.workbuddy.worker.parser.pdf.model.Token;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 页产物存储：内存/磁盘一致、字符与 token 事实保真、缺失窗口失败、临时目录清理。
 */
class PageArtifactStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void memoryStoreShouldRoundTrip() {
        try (PageArtifactStore store = new MemoryPageArtifactStore()) {
            store.writeWindow(1, List.of(page(1)));

            assertEquals(List.of(page(1)), store.readWindow(1));
        }
    }

    @Test
    void diskStoreShouldRoundTripAndCleanUp() {
        Path directory = tempDir.resolve("pages");
        try (PageArtifactStore store = new DiskPageArtifactStore(directory)) {
            store.writeWindow(1, List.of(page(1)));

            assertEquals(List.of(page(1)), store.readWindow(1));
        }

        assertFalse(Files.exists(directory), "页产物临时目录未清理");
    }

    @Test
    void missingWindowShouldFail() {
        try (PageArtifactStore store = new MemoryPageArtifactStore()) {
            assertThrows(PageArtifactStoreException.class, () -> store.readWindow(1));
        }
    }

    @Test
    void diskStoreShouldRejectMissingWindow() {
        Path directory = tempDir.resolve("missing");
        try (PageArtifactStore store = new DiskPageArtifactStore(directory)) {
            assertThrows(PageArtifactStoreException.class, () -> store.readWindow(1));
        }
    }

    @Test
    void openShouldChooseMemoryForSingleWindowAndDiskForMultiWindow() {
        assertTrue(PageArtifactStore.open(1, 1) instanceof MemoryPageArtifactStore);
        try (PageArtifactStore store = PageArtifactStore.open(2, 1)) {
            assertTrue(store instanceof DiskPageArtifactStore);
        }
    }

    /** 单页内容：字符、token、线段、图片框都带非默认值，确保编解码不丢字段 */
    private static PageContent page(int pageNo) {
        CharInfo charInfo = new CharInfo(72, 100, 5, 10, 10, "Helvetica", false, 'A');
        Token token = new Token("A", 72, 77);
        PageLine line = new PageLine(pageNo, 72, 100, 100, 10, "A", 10, "Helvetica", false,
                List.of(token), List.of(charInfo), false);
        PageMetric metric = new PageMetric();
        metric.setPage(pageNo);
        metric.setCharCount(1);
        metric.setGarbledRatio(0.01);
        metric.setCidMappingRatio(0.02);
        metric.setTextAreaRatio(0.3);
        metric.setImageAreaRatio(0.1);
        metric.setVectorAreaRatio(0.2);
        metric.setImageCount(1);
        return new PageContent(pageNo, 595, 842, new PdfPageGeometry(595, 842, 0), metric,
                List.of(line), List.of(new Segment(0, 0, 100, 0)),
                List.of(new Region(0, 0, 100, 100)));
    }
}
