package com.workbuddy.worker.parser.pdf.pipeline;

import com.workbuddy.common.utils.NullUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 已加载的 PDF 文档：持有打开的文档、可选临时文件与页数，关闭时一并释放。
 *
 * <p>临时文件只在输入超过内存阈值时存在；删除失败只告警，不覆盖文档关闭异常。
 */
@Slf4j
public final class LoadedPdf implements AutoCloseable {

    private final PDDocument document;
    private final Path tempFile;
    private final int pageCount;

    /**
     * 构造已加载文档。
     *
     * @param document 已打开的 PDF 文档
     * @param tempFile 临时文件；内存路径为 null
     * @param pageCount 页数
     */
    public LoadedPdf(PDDocument document, Path tempFile, int pageCount) {
        this.document = document;
        this.tempFile = tempFile;
        this.pageCount = pageCount;
    }

    /**
     * 已打开的 PDF 文档。
     *
     * @return PDFBox 文档
     */
    public PDDocument document() {
        return document;
    }

    /**
     * 页数。
     *
     * @return 文档页数
     */
    public int pageCount() {
        return pageCount;
    }

    /**
     * 关闭文档并删除临时文件。
     *
     * @throws IOException 关闭文档失败
     */
    @Override
    public void close() throws IOException {
        try {
            document.close();
        } finally {
            deleteQuietly(tempFile);
        }
    }

    private static void deleteQuietly(Path path) {
        if (NullUtil.isNull(path)) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("===> LoadedPdf 解析临时文件删除失败, path={}", path, e);
        }
    }
}
